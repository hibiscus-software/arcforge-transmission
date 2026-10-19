/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;

// An endpoint that moves FE (a Pin Insulator's port, a transformer): what the insulator HUD (InsulatorHud) shows under
// its wires, as the client last heard.
public interface PortHud {
    // How it's set (its mode, limit or direction), or null when it has nothing to show.
    @Nullable Component hudMode();

    FlowSolver.PortStatus status();

    // FE/t through it last tick.
    double lastFlow();

    // Whether the HUD shows its status and FE/t (not for a switch, only its position).
    default boolean hudFlow() {
        return true;
    }
}
