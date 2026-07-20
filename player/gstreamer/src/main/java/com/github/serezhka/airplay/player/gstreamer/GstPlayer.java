package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

import javax.swing.JComponent;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class GstPlayer implements AirPlayConsumer, AutoCloseable {

    private static final long VIDEO_QUEUE_BYTES = 4L * 1024 * 1024;
    private static final long AUDIO_QUEUE_BYTES = 256L * 1024;

    private final Pipeline videoPipeline;
    private final Pipeline alacPipeline;
    private final Pipeline aacEldPipeline;
    private final AppSrc videoSource;
    private final AppSrc alacSource;
    private final AppSrc aacEldSource;
    private final Element alacVolume;
    private final Element aacEldVolume;
    private final AppSink videoSink;
    private final Pad videoSinkPad;
    private final GstVideoComponent videoComponent;
    private final ScheduledExecutorService formatPoller;

    private volatile GstPlayerListener listener = new GstPlayerListener() {
    };
    private volatile AudioStreamInfo.CompressionType audioCompressionType;
    private volatile double volume = 1.0;
    private volatile boolean muted;
    private volatile int lastWidth;
    private volatile int lastHeight;

    public GstPlayer() {
        GstRuntime.RuntimeCheck runtime = GstRuntime.configure();
        if (!runtime.available()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), runtime.problems()));
        }
        Gst.init(Version.of(1, 20), "AirPlay Receiver");

        videoPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=video-source max-bytes=" + VIDEO_QUEUE_BYTES
                        + " max-buffers=12 block=false leaky-type=downstream "
                        + "! h264parse ! avdec_h264 ! videoconvert "
                        + "! appsink name=video-sink sync=false max-buffers=2 drop=true enable-last-sample=false");
        videoSource = (AppSrc) videoPipeline.getElementByName("video-source");
        configureSource(videoSource,
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au",
                VIDEO_QUEUE_BYTES);
        videoSink = (AppSink) videoPipeline.getElementByName("video-sink");
        videoSinkPad = videoSink.getStaticPad("sink");
        videoComponent = new GstVideoComponent(videoSink);
        videoComponent.setKeepAspect(true);

        alacPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=alac-source max-bytes=" + AUDIO_QUEUE_BYTES
                        + " max-buffers=128 block=false leaky-type=downstream "
                        + "! avdec_alac ! audioconvert ! audioresample " +
                        "! volume name=alac-volume ! autoaudiosink sync=false");
        alacSource = (AppSrc) alacPipeline.getElementByName("alac-source");
        configureSource(alacSource,
                "audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)44100," +
                        "stream-format=raw,codec_data=(buffer)00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44",
                AUDIO_QUEUE_BYTES);
        alacVolume = alacPipeline.getElementByName("alac-volume");

        aacEldPipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=aac-eld-source max-bytes=" + AUDIO_QUEUE_BYTES
                        + " max-buffers=128 block=false leaky-type=downstream "
                        + "! avdec_aac ! audioconvert ! audioresample " +
                        "! volume name=aac-eld-volume ! autoaudiosink sync=false");
        aacEldSource = (AppSrc) aacEldPipeline.getElementByName("aac-eld-source");
        configureSource(aacEldSource,
                "audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)44100," +
                        "stream-format=raw,codec_data=(buffer)f8e85000",
                AUDIO_QUEUE_BYTES);
        aacEldVolume = aacEldPipeline.getElementByName("aac-eld-volume");

        attachBusHandlers(videoPipeline);
        attachBusHandlers(alacPipeline);
        attachBusHandlers(aacEldPipeline);
        applyVolume();

        formatPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "airplay-video-format");
            thread.setDaemon(true);
            return thread;
        });
        formatPoller.scheduleWithFixedDelay(this::detectVideoFormat, 250, 500, TimeUnit.MILLISECONDS);
    }

    public JComponent videoComponent() {
        return videoComponent;
    }

    public void setListener(GstPlayerListener listener) {
        this.listener = Objects.requireNonNull(listener);
    }

    public void setVolume(double volume) {
        this.volume = normalizeVolume(volume);
        applyVolume();
    }

    public double volume() {
        return volume;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        applyVolume();
    }

    public boolean muted() {
        return muted;
    }

    @Override
    public synchronized void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        videoPipeline.play();
    }

    @Override
    public void onVideo(byte[] bytes) {
        push(videoSource, bytes);
    }

    @Override
    public synchronized void onVideoSrcDisconnect() {
        stopPipeline(videoPipeline);
        lastWidth = 0;
        lastHeight = 0;
    }

    @Override
    public synchronized void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        audioCompressionType = audioStreamInfo.getCompressionType();
        if (audioCompressionType == AudioStreamInfo.CompressionType.ALAC) {
            aacEldPipeline.stop();
            alacPipeline.play();
        } else {
            alacPipeline.stop();
            aacEldPipeline.play();
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        AudioStreamInfo.CompressionType type = audioCompressionType;
        if (type == AudioStreamInfo.CompressionType.ALAC) {
            push(alacSource, bytes);
        } else if (type == AudioStreamInfo.CompressionType.AAC_ELD) {
            push(aacEldSource, bytes);
        }
    }

    @Override
    public synchronized void onAudioSrcDisconnect() {
        stopPipeline(alacPipeline);
        stopPipeline(aacEldPipeline);
        audioCompressionType = null;
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        listener.onPlaybackError("Media URL casting is not supported in this release", null);
    }

    @Override
    public void close() {
        formatPoller.shutdownNow();
        onAudioSrcDisconnect();
        onVideoSrcDisconnect();
        videoPipeline.dispose();
        alacPipeline.dispose();
        aacEldPipeline.dispose();
        videoSinkPad.dispose();
    }

    private void configureSource(AppSrc source, String caps, long maxBytes) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        source.setCaps(Caps.fromString(caps));
        source.setMaxBytes(maxBytes);
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
    }

    private void attachBusHandlers(Pipeline pipeline) {
        pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> {
            log.error("GStreamer error {} from {}: {}", code, source, message);
            listener.onPlaybackError(message, null);
        });
        pipeline.getBus().connect((Bus.EOS) source -> listener.onEndOfStream());
    }

    private void applyVolume() {
        double effective = muted ? 0 : volume;
        alacVolume.set("volume", effective);
        aacEldVolume.set("volume", effective);
    }

    private void push(AppSrc source, byte[] bytes) {
        Buffer buffer = new Buffer(bytes.length);
        try {
            try {
                buffer.map(true).put(bytes);
            } finally {
                buffer.unmap();
            }
            FlowReturn result = source.pushBuffer(buffer);
            // gst_app_src_push_buffer takes ownership for every returned flow status.
            buffer.disown();
            if (result != FlowReturn.OK && result != FlowReturn.FLUSHING) {
                log.debug("GStreamer rejected an input buffer with status {}", result);
            }
        } catch (RuntimeException error) {
            buffer.dispose();
            throw error;
        }
    }

    private void stopPipeline(Pipeline pipeline) {
        pipeline.stop();
        pipeline.getState(750, TimeUnit.MILLISECONDS);
    }

    static double normalizeVolume(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void detectVideoFormat() {
        try {
            if (!videoPipeline.isPlaying() || !videoSinkPad.hasCurrentCaps()) {
                return;
            }
            Caps caps = videoSinkPad.getCurrentCaps();
            if (caps == null || caps.size() == 0) {
                return;
            }
            Structure format = caps.getStructure(0);
            int width = format.getInteger("width");
            int height = format.getInteger("height");
            if (width > 0 && height > 0 && (width != lastWidth || height != lastHeight)) {
                lastWidth = width;
                lastHeight = height;
                listener.onVideoFormatChanged(width, height);
            }
        } catch (RuntimeException error) {
            log.debug("Video caps are not available yet", error);
        }
    }
}
