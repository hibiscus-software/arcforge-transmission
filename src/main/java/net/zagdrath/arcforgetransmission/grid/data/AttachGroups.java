/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.data;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.zagdrath.arcforgetransmission.grid.CircuitType;
import net.zagdrath.arcforgetransmission.grid.Phase;

// Where wires attach on a model, read from its file in data/arcforgetransmission/attach_groups/<model>.json (one per
// model, derived from the model's geometry: each terminal is an insulator's tip, a suspension string's clamp, or a
// bushing's top). Each terminal is one insulator, wired on its own; a group is one circuit's insulators (the wrench swaps
// B and C across a group). Positions are px in the model's own north-facing frame (see the file); reserved points (the
// shield wire peaks) aren't wired yet.
public final class AttachGroups {
    private static final String ROOT = "/data/arcforgetransmission/attach_groups/";
    private static final Map<String, Model> CACHE = new ConcurrentHashMap<>();

    // One insulator: its starting phase label and where a wire attaches, px.
    public record Terminal(String label, double[] pos) {
        public Phase phase() {
            return Phase.byId(label);
        }
    }

    public record Group(String name, CircuitType circuit, List<Terminal> terminals) {
        // The group's middle, px.
        public double[] centre() {
            double[] c = new double[3];
            for (Terminal t : terminals) {
                for (int i = 0; i < 3; i++) {
                    c[i] += t.pos()[i] / terminals.size();
                }
            }
            return c;
        }
    }

    public record Reserved(String name, String use, double[] pos) {}

    public record Model(String model, List<Group> groups, List<Reserved> reserved) {}

    private AttachGroups() {}

    // The model's attach groups (read once).
    public static Model get(String model) {
        return CACHE.computeIfAbsent(model, AttachGroups::load);
    }

    private static Model load(String model) {
        try (InputStream in = AttachGroups.class.getResourceAsStream(ROOT + model + ".json")) {
            if (in == null) {
                throw new IllegalStateException("No attach groups for " + model);
            }
            return parse(model, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Couldn't read the attach groups for " + model, e);
        }
    }

    @SuppressWarnings("unchecked")
    static Model parse(String model, String text) {
        Map<String, Object> root = (Map<String, Object>) MiniJson.parse(text);
        List<Group> groups = new ArrayList<>();
        for (Object g : (List<Object>) root.get("groups")) {
            Map<String, Object> group = (Map<String, Object>) g;
            List<Terminal> terminals = new ArrayList<>();
            for (Object t : (List<Object>) group.get("terminals")) {
                Map<String, Object> terminal = (Map<String, Object>) t;
                terminals.add(new Terminal((String) terminal.get("label"), vector(terminal.get("pos"))));
            }
            groups.add(new Group((String) group.get("name"), CircuitType.byId((String) group.get("circuit")), List.copyOf(terminals)));
        }
        List<Reserved> reserved = new ArrayList<>();
        for (Object r : (List<Object>) root.getOrDefault("reserved", List.of())) {
            Map<String, Object> point = (Map<String, Object>) r;
            reserved.add(new Reserved((String) point.get("name"), (String) point.getOrDefault("use", ""), vector(point.get("pos"))));
        }
        return new Model(model, List.copyOf(groups), List.copyOf(reserved));
    }

    @SuppressWarnings("unchecked")
    private static double[] vector(Object value) {
        List<Object> list = (List<Object>) value;
        return new double[] { (Double) list.get(0), (Double) list.get(1), (Double) list.get(2) };
    }
}
