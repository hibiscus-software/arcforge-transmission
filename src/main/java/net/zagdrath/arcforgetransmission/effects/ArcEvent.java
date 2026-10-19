/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// Current interrupted with an arc, where and how hard: a breaker or recloser opening on fault current, a fuse blowing, a
// disconnect opened under load, a flashover to ground (lightning's too). Severity is the current over the device's (or
// the line's) rating. It's also the small packet the server sends the players near it (GridArcs), whose client draws the
// flash, the sparks and the sound (ArcEffects): nothing of it is spawned on the server.
public record ArcEvent(Vec3 at, Kind kind, VoltageTier tier, float severity) implements CustomPacketPayload {
    public enum Kind {
        BREAKER, FUSE, DISCONNECT, FLASHOVER
    }

    public static final Type<ArcEvent> TYPE = new Type<>(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "arc"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArcEvent> CODEC = StreamCodec.of((buf, arc) -> {
        buf.writeDouble(arc.at.x);
        buf.writeDouble(arc.at.y);
        buf.writeDouble(arc.at.z);
        buf.writeByte(arc.kind.ordinal());
        buf.writeByte(arc.tier.ordinal());
        buf.writeFloat(arc.severity);
    }, buf -> new ArcEvent(new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), Kind.values()[buf.readByte()],
            VoltageTier.values()[buf.readByte()], buf.readFloat()));

    @Override
    public Type<ArcEvent> type() {
        return TYPE;
    }
}
