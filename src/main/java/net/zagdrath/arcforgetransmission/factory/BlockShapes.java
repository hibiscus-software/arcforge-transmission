/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.zagdrath.arcforgetransmission.grid.data.MiniJson;

// The hitboxes tools/factory_block_model_gen.py writes beside each busway and tray block's models
// (data/arcforgetransmission/block_shapes/<block>.json): its pieces' boxes (px) and, as in its blockstate, which pieces a
// state shows. A state's shape is the union of its pieces' boxes, so it matches what's drawn; worked out once a state.
public final class BlockShapes {
    private static final String ROOT = "/data/arcforgetransmission/block_shapes/";
    private static final Map<String, ShapeFile> FILES = new ConcurrentHashMap<>();
    private static final Map<BlockState, VoxelShape> STATES = new ConcurrentHashMap<>();

    private record Piece(Map<String, Object> when, VoxelShape shape) {}

    private record ShapeFile(List<Piece> pieces) {}

    private BlockShapes() {}

    public static VoxelShape of(String block, BlockState state) {
        return STATES.computeIfAbsent(state, s -> {
            VoxelShape shape = Shapes.empty();
            for (Piece piece : file(block).pieces()) {
                if (matches(piece.when(), s)) {
                    shape = Shapes.or(shape, piece.shape());
                }
            }
            return shape.isEmpty() ? Block.box(6, 6, 6, 10, 10, 10) : shape.optimize();
        });
    }

    @SuppressWarnings("unchecked")
    private static ShapeFile file(String block) {
        return FILES.computeIfAbsent(block, b -> {
            try (InputStream in = BlockShapes.class.getResourceAsStream(ROOT + b + ".json")) {
                if (in == null) {
                    throw new IllegalStateException("No block shapes for " + b);
                }
                Map<String, Object> root = (Map<String, Object>) MiniJson.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                Map<String, Object> parts = (Map<String, Object>) root.get("parts");
                List<Piece> pieces = new ArrayList<>();
                for (Object entry : (List<Object>) root.get("multipart")) {
                    Map<String, Object> e = (Map<String, Object>) entry;
                    VoxelShape shape = Shapes.empty();
                    for (Object box : (List<Object>) parts.get((String) e.get("part"))) {
                        List<Object> v = (List<Object>) box;
                        shape = Shapes.or(shape, Block.box(num(v.get(0)), num(v.get(1)), num(v.get(2)), num(v.get(3)), num(v.get(4)), num(v.get(5))));
                    }
                    Object when = e.get("when");
                    pieces.add(new Piece(when == null ? Map.of() : (Map<String, Object>) when, shape));
                }
                return new ShapeFile(pieces);
            } catch (IOException e) {
                throw new IllegalStateException("Can't read the block shapes for " + b, e);
            }
        });
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

    // A multipart condition against a state: each property one of its |-separated values; OR and AND of conditions.
    @SuppressWarnings("unchecked")
    static boolean matches(Map<String, Object> when, BlockState state) {
        if (when.containsKey("OR")) {
            for (Object w : (List<Object>) when.get("OR")) {
                if (matches((Map<String, Object>) w, state)) {
                    return true;
                }
            }
            return false;
        }
        if (when.containsKey("AND")) {
            for (Object w : (List<Object>) when.get("AND")) {
                if (!matches((Map<String, Object>) w, state)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, Object> e : when.entrySet()) {
            String value = valueOf(state, e.getKey());
            boolean any = false;
            for (String allowed : String.valueOf(e.getValue()).split("\\|")) {
                any |= allowed.equals(value);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static String valueOf(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                Object v = state.getValue(property);
                return v instanceof StringRepresentable s ? s.getSerializedName() : String.valueOf(v);
            }
        }
        return "";
    }
}
