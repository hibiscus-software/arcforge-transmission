/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.effects;

// How a transformer's hum follows its load (pure, unit tested): silent de-energised; energised, its core hums even
// unloaded (`idle` of the full volume) and louder with its load, up to `volume` at its rating, its pitch rising by `rise`
// (about 5%) at full load. A change of volume fades in over `fadeTicks` (a step of the full volume each tick), never
// jumping.
public final class HumModel {
    private HumModel() {}

    // Its load, 0 to 1 (more than its rating hums as at its rating).
    public static double clamp(double load) {
        return Double.isNaN(load) ? 0 : Math.max(0, Math.min(1, load));
    }

    public static double volume(double load, boolean energized, double volume, double idle) {
        return energized ? volume * (idle + (1 - idle) * clamp(load)) : 0;
    }

    public static double pitch(double load, double rise) {
        return 1 + rise * clamp(load);
    }

    // One tick's step from `current` toward `target`: at most the full volume over fadeTicks.
    public static double approach(double current, double target, double volume, int fadeTicks) {
        double step = fadeTicks <= 0 ? Double.POSITIVE_INFINITY : volume / fadeTicks;
        return current < target ? Math.min(target, current + step) : Math.max(target, current - step);
    }
}
