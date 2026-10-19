/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

// Moves a tick's energy so none is ever lost: within one attempt (a transaction: nothing is kept unless it commits) the
// sources give what they will and each part of the grid's deliveries shrink to match, then the sinks take theirs. If a
// sink took less than it was given (it filled up since it was asked, or two ports share its room), that part of the grid
// tries again with its draws and deliveries cut to what was taken, so no FE drawn is ever left without a home. Parts of
// the grid are the ports joined through any network or transformer (`group`).
public final class EnergyCommit {
    private EnergyCommit() {}

    // One try at moving energy: draws and deliveries by port id, kept only if committed (closing without committing
    // undoes them all).
    public interface Attempt extends AutoCloseable {
        int draw(long port, int fe);

        int deliver(long port, int fe);

        void commit();

        @Override
        void close();
    }

    public record Moved(Map<Long, Integer> drawn, Map<Long, Integer> delivered) {
        public static final Moved NONE = new Moved(Map.of(), Map.of());
    }

    private static final int TRIES = 4;

    public static Moved run(Map<Long, Integer> draws, Map<Long, Integer> deliveries, Map<Long, Long> group, Supplier<Attempt> open) {
        Map<Long, Integer> wantDraw = new TreeMap<>(draws), wantDeliver = new TreeMap<>(deliveries);
        for (int attempt = 0; attempt < TRIES; attempt++) {
            try (Attempt a = open.get()) {
                Map<Long, Integer> drawn = new TreeMap<>(), delivered = new TreeMap<>();
                Map<Long, long[]> wantGot = new HashMap<>();
                for (Map.Entry<Long, Integer> draw : wantDraw.entrySet()) {
                    int got = draw.getValue() <= 0 ? 0 : Math.max(0, Math.min(draw.getValue(), a.draw(draw.getKey(), draw.getValue())));
                    drawn.put(draw.getKey(), got);
                    long[] wg = wantGot.computeIfAbsent(group.get(draw.getKey()), k -> new long[2]);
                    wg[0] += draw.getValue();
                    wg[1] += got;
                }
                // Each part's deliveries scaled to what its sources gave; what each sink took, and each part's shortfall.
                Map<Long, long[]> givenTaken = new HashMap<>();
                Map<Long, Integer> given = new TreeMap<>();
                for (Map.Entry<Long, Integer> delivery : wantDeliver.entrySet()) {
                    long[] wg = wantGot.get(group.get(delivery.getKey()));
                    double share = wg == null || wg[0] <= 0 ? 0.0 : Math.min(1.0, (double) wg[1] / wg[0]);
                    int give = (int) Math.floor(delivery.getValue() * share);
                    int took = give <= 0 ? 0 : Math.max(0, Math.min(give, a.deliver(delivery.getKey(), give)));
                    given.put(delivery.getKey(), give);
                    delivered.put(delivery.getKey(), took);
                    long[] gt = givenTaken.computeIfAbsent(group.get(delivery.getKey()), k -> new long[2]);
                    gt[0] += give;
                    gt[1] += took;
                }
                boolean short_ = false;
                for (long[] gt : givenTaken.values()) {
                    short_ |= gt[1] < gt[0];
                }
                if (!short_) {
                    a.commit();
                    return new Moved(drawn, delivered);
                }
                // Try again: where sinks took less, deliveries cut to what they took and draws cut by the same share.
                Map<Long, Integer> nextDraw = new TreeMap<>(), nextDeliver = new TreeMap<>();
                for (Map.Entry<Long, Integer> draw : wantDraw.entrySet()) {
                    long[] gt = givenTaken.get(group.get(draw.getKey()));
                    double share = gt == null || gt[0] <= 0 ? 1.0 : (double) gt[1] / gt[0];
                    nextDraw.put(draw.getKey(), (int) Math.ceil(drawn.get(draw.getKey()) * share - 1.0E-9));
                }
                for (Map.Entry<Long, Integer> delivery : wantDeliver.entrySet()) {
                    nextDeliver.put(delivery.getKey(), delivered.get(delivery.getKey()));
                }
                wantDraw = nextDraw;
                wantDeliver = nextDeliver;
            }
        }
        // Nothing would settle: nothing moves this tick.
        return Moved.NONE;
    }
}
