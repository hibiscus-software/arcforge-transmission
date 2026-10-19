/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

// Moving a tick's energy never loses FE a sink refuses: two service legs sharing one block's room, a sink that filled up
// since it was asked, and a source that gave less than it offered.
class EnergyCommitTest {
    // Energy buffers by name; ports point at them (two ports may share one). An attempt works on a copy.
    private static final class World {
        final Map<String, Integer> stored = new HashMap<>(), room = new HashMap<>();
        final Map<Long, String> buffer = new HashMap<>();

        EnergyCommit.Attempt open() {
            Map<String, Integer> s = new HashMap<>(stored), r = new HashMap<>(room);
            return new EnergyCommit.Attempt() {
                @Override
                public int draw(long port, int fe) {
                    String b = buffer.get(port);
                    int got = Math.min(fe, s.get(b));
                    s.merge(b, -got, Integer::sum);
                    r.merge(b, got, Integer::sum);
                    return got;
                }

                @Override
                public int deliver(long port, int fe) {
                    String b = buffer.get(port);
                    int took = Math.min(fe, r.get(b));
                    s.merge(b, took, Integer::sum);
                    r.merge(b, -took, Integer::sum);
                    return took;
                }

                @Override
                public void commit() {
                    stored.putAll(s);
                    room.putAll(r);
                }

                @Override
                public void close() {}
            };
        }

        void add(String name, int stored, int room, long... ports) {
            this.stored.put(name, stored);
            this.room.put(name, room);
            for (long port : ports) {
                buffer.put(port, name);
            }
        }

        int total() {
            return stored.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    @Test
    void twoLegsSharingOneBlocksRoomLoseNothing() {
        World w = new World();
        w.add("source", 10000, 0, 1);
        w.add("house", 0, 100, 2, 3);
        // The solver planned 100 to each leg (each asked the house alone) and 220 drawn (20 lost in the wires).
        EnergyCommit.Moved moved = EnergyCommit.run(Map.of(1L, 220), Map.of(2L, 100, 3L, 100), Map.of(1L, 0L, 2L, 0L, 3L, 0L), w::open);
        int drawn = moved.drawn().get(1L), delivered = moved.delivered().get(2L) + moved.delivered().get(3L);
        assertEquals(100, delivered, "the house took all it had room for");
        assertEquals(100, w.stored.get("house"));
        assertTrue(drawn <= 111, "the source gave only for what was taken (and its share of the loss): " + drawn);
        assertEquals(10000 - drawn + 100, w.total(), "drawn less delivered is only the wires' loss");
    }

    @Test
    void aSinkThatFilledUpTakesNoMoreThanItHasRoomFor() {
        World w = new World();
        w.add("source", 10000, 0, 1);
        w.add("full", 0, 0, 2);
        w.add("other", 0, 1000, 3, 4);
        EnergyCommit.Moved moved = EnergyCommit.run(Map.of(1L, 500, 4L, 0), Map.of(2L, 250, 3L, 250), Map.of(1L, 0L, 2L, 0L, 3L, 0L, 4L, 0L), w::open);
        assertEquals(0, moved.delivered().get(2L));
        assertEquals(250, moved.delivered().get(3L));
        assertEquals(250, moved.drawn().get(1L), "drawn only for the sink that took it");
        assertEquals(10000, w.total(), "nothing made or lost");
    }

    @Test
    void aSourceThatGaveLessShrinksItsPartsDeliveries() {
        World w = new World();
        w.add("source", 50, 0, 1);
        w.add("sink", 0, 1000, 2);
        w.add("far source", 1000, 0, 3);
        w.add("far sink", 0, 1000, 4);
        EnergyCommit.Moved moved = EnergyCommit.run(Map.of(1L, 100, 3L, 100), Map.of(2L, 100, 4L, 100), Map.of(1L, 0L, 2L, 0L, 3L, 1L, 4L, 1L), w::open);
        assertEquals(50, moved.delivered().get(2L), "its own part shrinks");
        assertEquals(100, moved.delivered().get(4L), "another part doesn't");
        assertEquals(1050, w.total(), "nothing made or lost");
    }
}
