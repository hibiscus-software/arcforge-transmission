/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.effects.HumModel;

// A looping sound that fades (as Arcforge's MachineLoopSound): it starts silent and moves toward the volume its owner sets
// each tick by a step (HumModel.approach: the full volume over fadeTicks), its pitch easing toward its target; told to
// fall silent, it fades out and stops. Its owner counts the client ticks since the sound engine last ran it (dropped()):
// one the engine has dropped stops running, and its owner starts another.
final class LoopSound extends AbstractTickableSoundInstance {
    private double target, targetPitch = 1.0;
    private final double full;
    private final int fadeTicks;
    private int idle;

    LoopSound(SoundEvent event, Vec3 at, double full, int fadeTicks) {
        super(event, SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.full = Math.max(full, 1.0E-3);
        this.fadeTicks = fadeTicks;
        looping = true;
        delay = 0;
        volume = 0;
        moveTo(at);
    }

    void target(double volume, double pitch, Vec3 at) {
        target = volume;
        targetPitch = pitch;
        moveTo(at);
    }

    void fadeOut() {
        target = 0;
    }

    private void moveTo(Vec3 at) {
        x = at.x;
        y = at.y;
        z = at.z;
    }

    @Override
    public void tick() {
        volume = (float) HumModel.approach(volume, target, full, fadeTicks);
        pitch += (float) ((targetPitch - pitch) * 0.2);
        if (target <= 0 && volume <= 0) {
            stop();
        }
        idle = 0;
    }

    // Whether the engine no longer runs it (stopped, or not ticked for a few client ticks).
    boolean dropped() {
        return isStopped() || ++idle > 5;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }
}
