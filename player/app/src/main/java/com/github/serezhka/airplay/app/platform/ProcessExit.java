package com.github.serezhka.airplay.app.platform;

import java.time.Duration;
import java.util.Objects;

/** Guarantees that native media or window teardown cannot leave the application resident. */
public final class ProcessExit {

    private static final Duration FORCE_EXIT_TIMEOUT = Duration.ofSeconds(5);

    private ProcessExit() {
    }

    public static void armWatchdog() {
        armWatchdog(FORCE_EXIT_TIMEOUT, () -> Runtime.getRuntime().halt(0));
    }

    static Thread armWatchdog(Duration timeout, Runnable forceExit) {
        Objects.requireNonNull(timeout);
        Objects.requireNonNull(forceExit);
        Thread watchdog = Thread.ofPlatform()
                .daemon(true)
                .name("airplay-force-exit")
                .unstarted(() -> {
                    try {
                        Thread.sleep(timeout.toMillis());
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    forceExit.run();
                });
        watchdog.start();
        return watchdog;
    }
}
