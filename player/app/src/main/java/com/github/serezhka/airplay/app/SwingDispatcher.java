package com.github.serezhka.airplay.app;

import javax.swing.SwingUtilities;

final class SwingDispatcher {

    private SwingDispatcher() {
    }

    static void dispatch(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
