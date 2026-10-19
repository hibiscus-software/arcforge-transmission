/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.Locale;

import com.mojang.serialization.Codec;

// Which way an inductor's block moves FE: not at all, from the block into the grid, or from the grid into the block.
public enum InductorMode {
    OFF,
    INPUT,
    OUTPUT;

    public static final Codec<InductorMode> CODEC = Codec.STRING.xmap(InductorMode::byId, InductorMode::id);

    public InductorMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static InductorMode byId(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OFF;
        }
    }
}
