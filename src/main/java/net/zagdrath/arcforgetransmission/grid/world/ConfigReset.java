/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.neoforged.neoforge.common.ModConfigSpec;

// Puts arcforgetransmission-common.toml's settings back to their defaults (see TransmissionCommands' /arcforgetransmission
// config reset), as Arcforge's ConfigReset does for its own file. A path is a setting's dotted path ("wires.ehv.maxSpan")
// or a section's ("wires.ehv"); "" is everything. Changes are written to the file at once and apply straight away (the
// grid reads its settings every time it uses them).
public final class ConfigReset {
    // Defaults raised since a file may have been written (each tier's volts and amps doubled): a file holding the old
    // value keeps it, and the reset listing says so until it's reset or changed.
    private static final Map<String, Double> OLD_DEFAULTS = Map.of("wires.lv.ratedAmps", 8.0, "wires.mv.ratedAmps", 16.0,
            "wires.hv.ratedAmps", 32.0, "wires.ehv.ratedAmps", 64.0, "transformers.poleRating", 1_024.0);

    private ConfigReset() {}

    // Every setting, by its dotted path, in the file's order.
    public static Map<String, ModConfigSpec.ConfigValue<?>> settings() {
        Map<String, ModConfigSpec.ConfigValue<?>> all = new LinkedHashMap<>();
        collect(GridConfig.SPEC.getValues(), "", all);
        return all;
    }

    private static void collect(UnmodifiableConfig config, String path, Map<String, ModConfigSpec.ConfigValue<?>> into) {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            String full = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            Object value = entry.getRawValue();
            if (value instanceof ModConfigSpec.ConfigValue<?> setting) {
                into.put(full, setting);
            } else if (value instanceof UnmodifiableConfig section) {
                collect(section, full, into);
            }
        }
    }

    // Every section's dotted path, for suggestions.
    public static List<String> sections() {
        List<String> sections = new ArrayList<>();
        for (String setting : settings().keySet()) {
            for (int dot = setting.indexOf('.'); dot >= 0; dot = setting.indexOf('.', dot + 1)) {
                String section = setting.substring(0, dot);
                if (!sections.contains(section)) {
                    sections.add(section);
                }
            }
        }
        return sections;
    }

    // Whether the path names a setting or a section ("" names everything).
    public static boolean exists(String path) {
        return path.isEmpty() || settings().containsKey(path) || sections().contains(path);
    }

    // The settings at or under the path that aren't at their defaults.
    public static List<String> changed(String path) {
        List<String> changed = new ArrayList<>();
        settings().forEach((name, setting) -> {
            if (under(name, path) && !isDefault(setting)) {
                changed.add(name);
            }
        });
        return changed;
    }

    // The settings still holding a default from before it was raised (see OLD_DEFAULTS).
    public static List<String> stale() {
        List<String> stale = new ArrayList<>();
        settings().forEach((name, setting) -> {
            Double old = OLD_DEFAULTS.get(name);
            if (old != null && setting.getRaw() instanceof Number value && value.doubleValue() == old) {
                stale.add(name);
            }
        });
        return stale;
    }

    // Resets the settings at or under the path and saves the file. Returns the paths that changed.
    public static List<String> reset(String path) {
        List<String> changed = changed(path);
        if (!changed.isEmpty()) {
            Map<String, ModConfigSpec.ConfigValue<?>> settings = settings();
            for (String name : changed) {
                resetValue(settings.get(name));
            }
            GridConfig.SPEC.save();
        }
        return changed;
    }

    private static boolean under(String name, String path) {
        return path.isEmpty() || name.equals(path) || name.startsWith(path + ".");
    }

    private static boolean isDefault(ModConfigSpec.ConfigValue<?> setting) {
        Object current = setting.getRaw();
        Object fallback = setting.getDefault();
        // The file may hold 3 where the default is 3.0, or a long where it's an int.
        if (current instanceof Number a && fallback instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return Objects.equals(current, fallback);
    }

    private static <T> void resetValue(ModConfigSpec.ConfigValue<T> setting) {
        setting.set(setting.getDefault());
    }
}
