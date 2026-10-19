/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.Locale;

import com.mojang.serialization.Codec;

// Which way a pin insulator's block (or a unit transformer's) moves FE: not at all, from the block into the grid, from the
// grid into the block, or as storage (a battery: it gives only what the grid's other sources can't cover, and takes only
// what they have spare).
public enum PinInsulatorMode {
    OFF,
    INPUT,
    OUTPUT,
    STORAGE;

    // Whether FE can leave the block for the grid / enter it from the grid in this mode.
    public boolean gives() {
        return this == INPUT || this == STORAGE;
    }

    public boolean takes() {
        return this == OUTPUT || this == STORAGE;
    }

    public static final Codec<PinInsulatorMode> CODEC = Codec.STRING.xmap(PinInsulatorMode::byId, PinInsulatorMode::id);

    public PinInsulatorMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PinInsulatorMode byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
