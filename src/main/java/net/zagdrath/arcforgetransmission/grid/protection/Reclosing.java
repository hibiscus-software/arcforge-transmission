/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

// A breaker's or recloser's reclosing: each trip is a shot, reclosing after `delay` ticks until the shots run out, when
// it locks out (closed again only by hand). Its shots are forgotten once it has held closed for FORGET ticks.
public final class Reclosing {
    public static final int FORGET = 600;

    private int shots;
    private boolean lockout;
    private long recloseAt, closedAt;

    public int shots() {
        return shots;
    }

    public boolean lockedOut() {
        return lockout;
    }

    // When it recloses (game time; 0 for never).
    public long recloseAt() {
        return recloseAt;
    }

    public long closedAt() {
        return closedAt;
    }

    // A trip at `now`: recloses `delay` ticks later unless that was past its last of `maxShots` shots.
    public void trip(long now, int maxShots, int delay) {
        shots++;
        lockout = shots > maxShots;
        recloseAt = lockout ? 0 : now + delay;
    }

    // Whether it's time to reclose.
    public boolean due(long now) {
        return !lockout && recloseAt > 0 && now >= recloseAt;
    }

    // It closed (by itself or by hand) at `now`.
    public void closed(long now) {
        recloseAt = 0;
        closedAt = now;
    }

    // Opened by hand: no reclose.
    public void cancel() {
        recloseAt = 0;
    }

    // Locked out at once (a differential trip, or a reclose onto a grounded line).
    public void lockOut() {
        lockout = true;
        recloseAt = 0;
    }

    // Closed by hand: ready again.
    public void reset() {
        lockout = false;
        shots = 0;
        recloseAt = 0;
    }

    // Forgets the shots once it has held closed long enough.
    public void forget(long now) {
        if (shots > 0 && now - closedAt > FORGET) {
            shots = 0;
        }
    }

    public void restore(int shots, boolean lockout, long recloseAt, long closedAt) {
        this.shots = shots;
        this.lockout = lockout;
        this.recloseAt = recloseAt;
        this.closedAt = closedAt;
    }
}
