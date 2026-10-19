/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// One wire as its owning end draws it (the end it was strung from): from that insulator to the other, world positions,
// and what it is (bare, insulated, a shield wire; a guy, for a tower's guys). Synced with the endpoint's block entity.
public record WireView(long edge, Vec3 from, Vec3 to, VoltageTier tier, String kind) {
    public static final String GUY = "guy";

    public boolean insulated() {
        return kind.equals("insulated");
    }
    static final Codec<Vec3> VEC = Codec.DOUBLE.listOf().xmap(list -> new Vec3(list.get(0), list.get(1), list.get(2)),
            v -> List.of(v.x, v.y, v.z));
    static final Codec<VoltageTier> TIER = Codec.STRING.xmap(VoltageTier::byId, VoltageTier::id);
    public static final Codec<WireView> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("edge").forGetter(WireView::edge),
            VEC.fieldOf("from").forGetter(WireView::from),
            VEC.fieldOf("to").forGetter(WireView::to),
            TIER.fieldOf("tier").forGetter(WireView::tier),
            Codec.STRING.optionalFieldOf("kind", "bare").forGetter(WireView::kind)).apply(i, WireView::new));
    public static final Codec<List<WireView>> LIST_CODEC = CODEC.listOf();
}
