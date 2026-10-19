/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

// The fault-event hook: anything (breakers, grounding, logging, tests) can listen for every fault the grid raises.
public final class GridFaults {
    private static final List<Consumer<FaultEvent>> LISTENERS = new CopyOnWriteArrayList<>();

    private GridFaults() {}

    public static void listen(Consumer<FaultEvent> listener) {
        LISTENERS.add(listener);
    }

    public static void unlisten(Consumer<FaultEvent> listener) {
        LISTENERS.remove(listener);
    }

    public static void raise(FaultEvent event) {
        for (Consumer<FaultEvent> listener : LISTENERS) {
            listener.accept(event);
        }
    }
}
