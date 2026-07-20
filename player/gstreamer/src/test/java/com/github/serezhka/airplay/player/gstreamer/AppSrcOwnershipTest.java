package com.github.serezhka.airplay.player.gstreamer;

import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.elements.AppSrc;
import org.freedesktop.gstreamer.lowlevel.AppAPI;
import org.freedesktop.gstreamer.lowlevel.annotations.Invalidate;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSrcOwnershipTest {

    @Test
    void nativePushInvalidatesBufferAfterTakingOwnership() throws Exception {
        Method push = AppAPI.class.getMethod("gst_app_src_push_buffer", AppSrc.class, Buffer.class);
        Annotation[] bufferAnnotations = push.getParameterAnnotations()[1];

        assertTrue(Arrays.stream(bufferAnnotations).anyMatch(Invalidate.class::isInstance),
                "The caller must not dispose or disown a Buffer after AppSrc.pushBuffer");
    }
}
