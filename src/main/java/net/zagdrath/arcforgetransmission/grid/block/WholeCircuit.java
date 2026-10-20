/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

// An endpoint whose conductors are one circuit's, labelled together (a Cable Termination): the Wrench on Port switches
// them all between three-phase and split-phase at once, and says how that went.
public interface WholeCircuit {
    Component switchCircuit(ServerLevel level);
}
