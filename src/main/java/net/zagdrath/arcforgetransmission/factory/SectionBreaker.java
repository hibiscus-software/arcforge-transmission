/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.protection.FuseElement;
import net.zagdrath.arcforgetransmission.grid.protection.Overcurrent;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;

// One switching device in a lineup section, on the grid's own protection (Overcurrent's graded curves, FuseElement's
// melting): an LV or MV incomer's load-break switch with its current-limiting fuses, or a breaker with a trip unit
// (long-time: an inverse curve over its pickup; short-time: a definite delay over a multiple of that; instantaneous: at
// once over a multiple of its frame, only when nothing protective lies between it and the fault). Graded like every
// breaker on the grid: each protective device between it and the trouble adds the grading interval, so the one nearest
// a fault or overload trips and the rest hold. Closed, open, or tripped (a blown fuse, a tripped breaker: reset by hand
// before it closes again). Pure: the section feeds it each tick's current and its settings.
public final class SectionBreaker {
    // The trip unit's steps, set with the Wrench on Configure: the long-time pickup in eighths of the frame, the short-time
    // pickup and the instantaneous as multiples (the instantaneous 0: off).
    public static final int[] SHORT_TIME = { 2, 3, 4, 6, 8 }, INSTANT = { 0, 6, 8, 10, 12 };
    // A feeder's frame, as a multiple of its bus's wire (1W, 2W, 4W, 8W).
    public static final int[] FRAMES = { 1, 2, 4, 8 };

    public enum Setting {
        LONG_TIME, SHORT_TIME, INSTANT
    }

    private OpState state = OpState.OPEN;
    private int frame = 0, longTimeEighths = 8, shortTime = 2, instant = 3;
    // Which setting Configure steps (Sneak + Configure picks it).
    private Setting focus = Setting.LONG_TIME;
    private final Overcurrent longTimeElement = new Overcurrent(), shortTimeElement = new Overcurrent();
    private final FuseElement fuse = new FuseElement();

    public OpState state() {
        return state;
    }

    public boolean closed() {
        return state == OpState.CLOSED;
    }

    public void setState(OpState state) {
        this.state = state;
        if (state != OpState.CLOSED) {
            longTimeElement.reset();
            shortTimeElement.reset();
        }
    }

    public int frame() {
        return frame;
    }

    public void setFrame(int frame) {
        this.frame = Math.floorMod(frame, FRAMES.length);
    }

    public Setting focus() {
        return focus;
    }

    public void nextFocus() {
        focus = Setting.values()[(focus.ordinal() + 1) % Setting.values().length];
    }

    // Steps the setting in focus; returns its new value as a multiple (long-time: of the frame).
    public double stepFocus() {
        switch (focus) {
            case LONG_TIME -> longTimeEighths = longTimeEighths >= 8 ? 4 : longTimeEighths + 1;
            case SHORT_TIME -> shortTime = (shortTime + 1) % SHORT_TIME.length;
            case INSTANT -> instant = (instant + 1) % INSTANT.length;
        }
        return focusValue();
    }

    public double focusValue() {
        return switch (focus) {
            case LONG_TIME -> longTimeEighths / 8.0;
            case SHORT_TIME -> SHORT_TIME[shortTime];
            case INSTANT -> INSTANT[instant];
        };
    }

    // Its long-time pickup, A, on a frame of `frameAmps`.
    public double pickup(double frameAmps) {
        return frameAmps * longTimeEighths / 8.0;
    }

    // One tick of a breaker on a frame of frameAmps carrying `amps`, `beyond` between it and the trouble; true when it
    // trips (it's then TRIPPED).
    public boolean tickBreaker(double amps, FlowSolver.Beyond beyond, double frameAmps, double timeDial, int shortTimeTicks, int interval,
            double fuseMelt) {
        if (state != OpState.CLOSED) {
            return false;
        }
        double lt = pickup(frameAmps);
        Overcurrent.Settings longTime = new Overcurrent.Settings(lt, 0, timeDial, true, 0, interval, false, 0);
        double inst = INSTANT[instant] * frameAmps;
        Overcurrent.Settings shortTimeSettings = new Overcurrent.Settings(lt * SHORT_TIME[shortTime], inst, 0, false, shortTimeTicks, interval, false, 0);
        boolean trip = longTimeElement.tick(amps, beyond, 0, longTime, fuseMelt);
        trip |= shortTimeElement.tick(amps, beyond, 0, shortTimeSettings, fuseMelt);
        if (trip) {
            setState(OpState.TRIPPED);
        }
        return trip;
    }

    // One tick of a fused switch whose fuses are rated `fuseAmps`, carrying `amps`; true when a fuse blows (it's then
    // TRIPPED: the switch has opened on its striker).
    public boolean tickFuse(double amps, double fuseAmps, double melt, double cooling) {
        if (state != OpState.CLOSED) {
            return false;
        }
        if (fuse.tick(amps / fuseAmps, melt, cooling)) {
            setState(OpState.TRIPPED);
            return true;
        }
        return false;
    }

    // Saved as one int a field: the state, frame, settings and focus.
    public int[] save() {
        return new int[] { state.ordinal(), frame, longTimeEighths, shortTime, instant, focus.ordinal() };
    }

    public void load(int[] saved) {
        if (saved.length >= 6) {
            state = OpState.values()[Math.floorMod(saved[0], OpState.values().length)];
            frame = Math.floorMod(saved[1], FRAMES.length);
            longTimeEighths = Math.max(4, Math.min(8, saved[2]));
            shortTime = Math.floorMod(saved[3], SHORT_TIME.length);
            instant = Math.floorMod(saved[4], INSTANT.length);
            focus = Setting.values()[Math.floorMod(saved[5], Setting.values().length)];
        }
    }
}
