/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Which saved node each of a block's insulators is: by its stable key (its group's name and its terminal's label, so a
// model that gains, loses or reorders insulators keeps every wire on the right one), or, for a node saved before keys
// (an empty key), by its place in the block's list, which then takes the key (migrating the save).
public final class InsulatorMatch {
    private InsulatorMatch() {}

    // For each insulator (its key), the index of the saved node it is, or -1 for a new one.
    public static int[] match(List<String> savedKeys, List<String> keys) {
        // Saved nodes by key, in order (a key a group has twice, two neutrals, is matched first to first).
        Map<String, ArrayDeque<Integer>> byKey = new HashMap<>();
        for (int i = 0; i < savedKeys.size(); i++) {
            String key = savedKeys.get(i);
            if (key != null && !key.isEmpty()) {
                byKey.computeIfAbsent(key, k -> new ArrayDeque<>()).add(i);
            }
        }
        int[] out = new int[keys.size()];
        Arrays.fill(out, -1);
        boolean[] used = new boolean[savedKeys.size()];
        for (int i = 0; i < keys.size(); i++) {
            ArrayDeque<Integer> same = keys.get(i).isEmpty() ? null : byKey.get(keys.get(i));
            Integer at = same == null ? null : same.poll();
            if (at != null) {
                out[i] = at;
                used[at] = true;
            }
        }
        // Saved before keys: by place.
        for (int i = 0; i < keys.size(); i++) {
            if (out[i] < 0 && i < savedKeys.size() && !used[i] && savedKeys.get(i) != null && savedKeys.get(i).isEmpty()) {
                out[i] = i;
                used[i] = true;
            }
        }
        return out;
    }
}
