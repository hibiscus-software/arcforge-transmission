/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects;

import net.minecraft.world.phys.Vec3;

// A transformer that hums (substation, unit, pole-mount and MV distribution): read on the client from what its HUD is
// sent (TransformerHums).
public interface Humming {
    // Whether this one hums (a tower block entity is a transformer only for some tiers).
    boolean hums();

    // Its load, a share of its rating (what it passed last, as its HUD shows).
    double humLoad();

    // Whether it's energised (any of its insulators live).
    boolean humEnergized();

    // Where the hum comes from.
    Vec3 humAt();
}
