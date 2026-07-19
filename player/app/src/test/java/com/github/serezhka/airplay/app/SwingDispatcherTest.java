package com.github.serezhka.airplay.app;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingDispatcherTest {

    @Test
    void movesUiCallbacksOntoSwingEventThread() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        AtomicBoolean onEventThread = new AtomicBoolean();

        SwingDispatcher.dispatch(() -> {
            onEventThread.set(SwingUtilities.isEventDispatchThread());
            called.countDown();
        });

        assertTrue(called.await(2, TimeUnit.SECONDS));
        assertTrue(onEventThread.get());
    }
}
