/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

// Saved nodes are matched to a block's insulators by key, so a model that changes keeps every wire where it was; a save
// from before keys is matched by place.
class InsulatorMatchTest {
    @Test
    void aReorderedModelKeepsEachNode() {
        List<String> saved = List.of("line/A", "line/B", "line/C");
        assertArrayEquals(new int[] { 2, 0, 1 }, InsulatorMatch.match(saved, List.of("line/C", "line/A", "line/B")));
    }

    @Test
    void aModelThatGainsAnInsulatorAddsOneNode() {
        List<String> saved = List.of("line/A", "line/B", "line/C");
        assertArrayEquals(new int[] { -1, 0, 1, 2 }, InsulatorMatch.match(saved, List.of("shield/S", "line/A", "line/B", "line/C")));
    }

    @Test
    void aModelThatLosesOneDropsOnlyThatNode() {
        List<String> saved = List.of("line/A", "neutral/N", "line/B");
        assertArrayEquals(new int[] { 0, 2 }, InsulatorMatch.match(saved, List.of("line/A", "line/B")));
    }

    @Test
    void aKeyAGroupHasTwiceIsMatchedInOrder() {
        assertArrayEquals(new int[] { 1, 0, 2 }, InsulatorMatch.match(List.of("lv/N", "lv/L1", "lv/N"), List.of("lv/L1", "lv/N", "lv/N")));
    }

    @Test
    void anOldSaveIsMatchedByPlace() {
        List<String> saved = Arrays.asList("", "", "");
        assertArrayEquals(new int[] { 0, 1, 2 }, InsulatorMatch.match(saved, List.of("line/A", "line/B", "line/C")));
        assertArrayEquals(new int[] { 0, 1, -1 }, InsulatorMatch.match(List.of("", ""), List.of("line/A", "line/B", "line/C")));
    }
}
