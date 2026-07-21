package com.github.serezhka.airplay.app.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessExitTest {

    @Test
    void forcesExitWhenGracefulShutdownStalls() throws Exception {
        CountDownLatch forced = new CountDownLatch(1);

        ProcessExit.armWatchdog(Duration.ofMillis(10), forced::countDown);

        assertTrue(forced.await(1, TimeUnit.SECONDS));
    }

    @Test
    void interruptedWatchdogDoesNotForceExit() throws Exception {
        CountDownLatch forced = new CountDownLatch(1);
        Thread watchdog = ProcessExit.armWatchdog(Duration.ofSeconds(1), forced::countDown);

        watchdog.interrupt();
        watchdog.join(1000);

        assertFalse(forced.await(20, TimeUnit.MILLISECONDS));
    }
}
