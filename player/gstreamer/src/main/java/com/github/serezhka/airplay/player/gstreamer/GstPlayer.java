package com.github.serezhka.airplay.player.gstreamer;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;
import lombok.extern.slf4j.Slf4j;
import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Format;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pad;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Structure;
import org.freedesktop.gstreamer.Version;
import org.freedesktop.gstreamer.elements.AppSink;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.swing.GstVideoComponent;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public final class GstPlayer implements AirPlayConsumer, AutoCloseable {

    private static final long VIDEO_QUEUE_BYTES = 4L * 1024 * 1024;
    private static final long AUDIO_QUEUE_BYTES = 1024L * 1024;

    private final Object videoLock = new Object();
    private final Object audioLock = new Object();
    private final JPanel videoHost = new JPanel(new BorderLayout());
    private final ScheduledExecutorService formatPoller;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile VideoPipeline video;
    private volatile AudioPipeline audio;
    private volatile GstPlayerListener listener = new GstPlayerListener() {
    };
    private volatile AudioStreamInfo.CompressionType audioCompressionType;
    private volatile double volume = 1.0;
    private volatile boolean muted;
    private volatile int lastWidth;
    private volatile int lastHeight;
    private volatile ScheduledFuture<?> memoryReclaim;

    public GstPlayer() {
        GstRuntime.RuntimeCheck runtime = GstRuntime.configure();
        if (!runtime.available()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), runtime.problems()));
        }
        Gst.init(Version.of(1, 20), "AirPlay Receiver");

        videoHost.setBackground(Color.BLACK);
        videoHost.setOpaque(true);
        formatPoller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "airplay-video-format");
            thread.setDaemon(true);
            return thread;
        });

        video = createVideoPipeline();
        installVideoComponent(video.component());
        validateAudioPipelines();
        formatPoller.scheduleWithFixedDelay(this::detectVideoFormat, 250, 500, TimeUnit.MILLISECONDS);
    }

    public JComponent videoComponent() {
        return videoHost;
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
    public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
        synchronized (videoLock) {
            if (closed.get()) {
                return;
            }
            if (video == null) {
                video = createVideoPipeline();
                installVideoComponent(video.component());
            }
            video.pipeline().play();
        }
    }

    @Override
    public void onVideo(byte[] bytes) {
        synchronized (videoLock) {
            VideoPipeline current = video;
            if (current != null && !closed.get()) {
                push(current.source(), bytes);
            }
        }
    }

    @Override
    public void onVideoSrcDisconnect() {
        synchronized (videoLock) {
            releaseVideoPipeline(video);
            video = null;
            lastWidth = 0;
            lastHeight = 0;
            if (!closed.get()) {
                video = createVideoPipeline();
                installVideoComponent(video.component());
            }
        }
        requestMemoryReclaim();
    }

    @Override
    public void onAudioFormat(AudioStreamInfo audioStreamInfo) {
        synchronized (audioLock) {
            if (closed.get()) {
                return;
            }
            releaseAudioPipeline(audio);
            audioCompressionType = audioStreamInfo.getCompressionType();
            audio = createAudioPipeline(audioCompressionType);
            applyVolume(audio);
            audio.pipeline().play();
        }
    }

    @Override
    public void onAudio(byte[] bytes) {
        synchronized (audioLock) {
            AudioPipeline current = audio;
            if (current != null && current.type() == audioCompressionType && !closed.get()) {
                push(current.source(), bytes);
            }
        }
    }

    @Override
    public void onAudioSrcDisconnect() {
        synchronized (audioLock) {
            releaseAudioPipeline(audio);
            audio = null;
            audioCompressionType = null;
        }
        requestMemoryReclaim();
    }

    @Override
    public void onMediaPlaylist(String playlistUri) {
        listener.onPlaybackError("Media URL casting is not supported in this release", null);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        formatPoller.shutdownNow();
        synchronized (audioLock) {
            releaseAudioPipeline(audio);
            audio = null;
            audioCompressionType = null;
        }
        synchronized (videoLock) {
            releaseVideoPipeline(video);
            video = null;
        }
    }

    private VideoPipeline createVideoPipeline() {
        Pipeline pipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=video-source max-bytes=" + VIDEO_QUEUE_BYTES
                        + " max-buffers=12 block=false leaky-type=downstream "
                        + "! h264parse ! avdec_h264 ! videoconvert "
                        + "! appsink name=video-sink sync=false max-buffers=2 drop=true enable-last-sample=false");
        AppSrc source = (AppSrc) pipeline.getElementByName("video-source");
        configureSource(source,
                "video/x-h264,colorimetry=bt709,stream-format=(string)byte-stream,alignment=(string)au",
                VIDEO_QUEUE_BYTES, false);
        AppSink sink = (AppSink) pipeline.getElementByName("video-sink");
        Pad sinkPad = sink.getStaticPad("sink");
        GstVideoComponent component = createVideoComponent(sink);
        Bus bus = pipeline.getBus();
        attachBusHandlers(bus);
        return new VideoPipeline(pipeline, source, sink, sinkPad, bus, component);
    }

    private AudioPipeline createAudioPipeline(AudioStreamInfo.CompressionType type) {
        String decoder;
        String caps;
        if (type == AudioStreamInfo.CompressionType.ALAC) {
            decoder = "avdec_alac";
            caps = "audio/x-alac,mpegversion=(int)4,channels=(int)2,rate=(int)44100,"
                    + "stream-format=raw,codec_data=(buffer)"
                    + "00000024616c616300000000000001600010280a0e0200ff00000000000000000000ac44";
        } else {
            decoder = "avdec_aac";
            caps = "audio/mpeg,mpegversion=(int)4,channels=(int)2,rate=(int)44100,"
                    + "stream-format=raw,codec_data=(buffer)f8e85000";
        }

        Pipeline pipeline = (Pipeline) Gst.parseLaunch(
                "appsrc name=audio-source max-bytes=" + AUDIO_QUEUE_BYTES
                        + " max-buffers=512 block=true "
                        + "! " + decoder + " ! audioconvert ! audioresample "
                        + "! volume name=audio-volume ! autoaudiosink sync=false");
        AppSrc source = (AppSrc) pipeline.getElementByName("audio-source");
        configureSource(source, caps, AUDIO_QUEUE_BYTES, false);
        Element volumeElement = pipeline.getElementByName("audio-volume");
        Bus bus = pipeline.getBus();
        attachBusHandlers(bus);
        return new AudioPipeline(type, pipeline, source, volumeElement, bus);
    }

    private GstVideoComponent createVideoComponent(AppSink sink) {
        final GstVideoComponent[] result = new GstVideoComponent[1];
        Runnable create = () -> {
            result[0] = new GstVideoComponent(sink);
            result[0].setKeepAspect(true);
        };
        runOnEdtAndWait(create);
        return result[0];
    }

    private void installVideoComponent(GstVideoComponent component) {
        runOnEdtAndWait(() -> {
            videoHost.removeAll();
            if (component != null) {
                videoHost.add(component, BorderLayout.CENTER);
            }
            videoHost.revalidate();
            videoHost.repaint();
        });
    }

    private void configureSource(AppSrc source, String caps, long maxBytes, boolean timestamp) {
        source.setStreamType(AppSrc.StreamType.STREAM);
        try (Caps parsedCaps = Caps.fromString(caps)) {
            source.setCaps(parsedCaps);
        }
        source.setMaxBytes(maxBytes);
        source.set("is-live", true);
        source.set("format", Format.TIME);
        source.set("emit-signals", false);
        source.set("do-timestamp", timestamp);
    }

    private void attachBusHandlers(Bus bus) {
        bus.connect((Bus.ERROR) (source, code, message) -> {
            log.error("GStreamer error {} from {}: {}", code, source, message);
            listener.onPlaybackError(message, null);
        });
        bus.connect((Bus.EOS) source -> listener.onEndOfStream());
    }

    private void applyVolume() {
        synchronized (audioLock) {
            applyVolume(audio);
        }
    }

    private void applyVolume(AudioPipeline current) {
        if (current != null) {
            current.volume().set("volume", muted ? 0 : volume);
        }
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

    private void releaseVideoPipeline(VideoPipeline current) {
        if (current == null) {
            return;
        }
        installVideoComponent(null);
        stopPipeline(current.pipeline());
        current.sinkPad().dispose();
        current.source().dispose();
        current.sink().dispose();
        current.bus().dispose();
        current.pipeline().dispose();
    }

    private void releaseAudioPipeline(AudioPipeline current) {
        if (current == null) {
            return;
        }
        stopPipeline(current.pipeline());
        current.source().dispose();
        current.volume().dispose();
        current.bus().dispose();
        current.pipeline().dispose();
    }

    private void stopPipeline(Pipeline pipeline) {
        pipeline.stop();
        pipeline.getState(500, TimeUnit.MILLISECONDS);
    }

    private void validateAudioPipelines() {
        AudioPipeline alac = createAudioPipeline(AudioStreamInfo.CompressionType.ALAC);
        releaseAudioPipeline(alac);
        AudioPipeline aacEld = createAudioPipeline(AudioStreamInfo.CompressionType.AAC_ELD);
        releaseAudioPipeline(aacEld);
    }

    private void requestMemoryReclaim() {
        if (closed.get() || formatPoller.isShutdown()) {
            return;
        }
        ScheduledFuture<?> previous = memoryReclaim;
        if (previous != null) {
            previous.cancel(false);
        }
        try {
            memoryReclaim = formatPoller.schedule(System::gc, 1500, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // Application shutdown won the race.
        }
    }

    private void detectVideoFormat() {
        VideoPipeline current = video;
        if (current == null) {
            return;
        }
        try {
            if (!current.pipeline().isPlaying() || !current.sinkPad().hasCurrentCaps()) {
                return;
            }
            try (Caps caps = current.sinkPad().getCurrentCaps()) {
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
            }
        } catch (RuntimeException error) {
            log.debug("Video caps are not available yet", error);
        }
    }

    private void runOnEdtAndWait(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while updating the video component", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Unable to update the video component", error.getCause());
        }
    }

    static double normalizeVolume(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record VideoPipeline(Pipeline pipeline,
                                 AppSrc source,
                                 AppSink sink,
                                 Pad sinkPad,
                                 Bus bus,
                                 GstVideoComponent component) {
    }

    private record AudioPipeline(AudioStreamInfo.CompressionType type,
                                 Pipeline pipeline,
                                 AppSrc source,
                                 Element volume,
                                 Bus bus) {
    }
}
