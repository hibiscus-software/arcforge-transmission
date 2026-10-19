/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;

// The grid's sounds (made by tools/transmission_sound_gen.py, named in sounds.json with their subtitles): a transformer's
// hum, corona's buzz on a wet EHV line, and an interruption's crack, a fuse's pop and a breaker's clunk.
public final class TransmissionSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, ArcforgeTransmission.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> TRANSFORMER_HUM = register("transformer_hum");
    public static final DeferredHolder<SoundEvent, SoundEvent> CORONA_BUZZ = register("corona_buzz");
    public static final DeferredHolder<SoundEvent, SoundEvent> ARC_CRACK = register("arc_crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> FUSE_POP = register("fuse_pop");
    public static final DeferredHolder<SoundEvent, SoundEvent> BREAKER_CLUNK = register("breaker_clunk");

    private TransmissionSounds() {}

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, name)));
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}
