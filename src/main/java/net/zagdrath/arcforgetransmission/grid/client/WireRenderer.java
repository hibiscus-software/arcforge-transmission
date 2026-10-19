/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.world.WireView;

// Draws wires: each a single conductor hanging in its curve (Catenary: 0.05 x span of sag at mid-span) from one
// insulator to the other. A wire is a solid square tube the tier's width across (LV 1 x 1 px), in segments of at most
// half a block, its four sides lit by their own normals and coloured from the tier's flat sprite (block/wire/<tier>), so
// nothing on it shimmers at a distance. The EV superconductor glows full-bright over its dark sheath
// (block/wire/ev_wire_glow).
public final class WireRenderer {
    public static final Identifier GLOW = Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "block/wire/ev_wire_glow");

    // A wire ready to draw: its ends relative to the block drawing it, its tier and light.
    public record Prepared(float[] from, float[] to, VoltageTier tier, int light) {}

    private WireRenderer() {}

    public static Identifier sprite(VoltageTier tier) {
        return Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "block/wire/" + tier.id());
    }

    // The wires a block draws, ready for the render thread (positions relative to origin, the light at each middle).
    public static List<Prepared> prepare(List<WireView> wires, BlockPos origin, @Nullable Level level) {
        List<Prepared> out = new ArrayList<>(wires.size());
        for (WireView wire : wires) {
            float[] from = { (float) (wire.from().x - origin.getX()), (float) (wire.from().y - origin.getY()), (float) (wire.from().z - origin.getZ()) };
            float[] to = { (float) (wire.to().x - origin.getX()), (float) (wire.to().y - origin.getY()), (float) (wire.to().z - origin.getZ()) };
            double[] mid = Catenary.point(new double[] { wire.from().x, wire.from().y, wire.from().z }, new double[] { wire.to().x, wire.to().y, wire.to().z }, 0.5);
            int light = level != null ? LightCoordsUtil.getLightCoords(level, BlockPos.containing(mid[0], mid[1], mid[2])) : LightCoordsUtil.FULL_BRIGHT;
            out.add(new Prepared(from, to, wire.tier(), light));
        }
        return out;
    }

    public static void submit(List<Prepared> wires, PoseStack poseStack, SubmitNodeCollector collector) {
        if (wires.isEmpty()) {
            return;
        }
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite glow = atlas.getSprite(GLOW);
        Identifier location = glow.atlasLocation();
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(location), (pose, buffer) -> {
            for (Prepared wire : wires) {
                draw(pose, buffer, wire, atlas.getSprite(sprite(wire.tier())), wire.light());
            }
        });
        boolean anyEv = wires.stream().anyMatch(w -> w.tier() == VoltageTier.EV);
        if (anyEv) {
            collector.submitCustomGeometry(poseStack, RenderTypes.eyes(location), (pose, buffer) -> {
                for (Prepared wire : wires) {
                    if (wire.tier() == VoltageTier.EV) {
                        draw(pose, buffer, wire, glow, LightCoordsUtil.FULL_BRIGHT);
                    }
                }
            });
        }
    }

    private static void draw(PoseStack.Pose pose, VertexConsumer buffer, Prepared wire, TextureAtlasSprite sprite, int light) {
        double[] a = { wire.from()[0], wire.from()[1], wire.from()[2] }, b = { wire.to()[0], wire.to()[1], wire.to()[2] };
        double length = Catenary.length(a, b);
        int segments = Math.max(4, (int) Math.ceil(length * 2.0));
        double half = wire.tier().wireWidthPx() / 32.0;
        double[][] points = new double[segments + 1][];
        for (int i = 0; i <= segments; i++) {
            points[i] = Catenary.point(a, b, (double) i / segments);
        }
        // Each point's frame (flat: level and square to the wire; up: square to both), from the wire's direction there,
        // so neighbouring segments share their corners and the tube has no gaps or seams where it bends.
        double[][] flats = new double[segments + 1][], ups = new double[segments + 1][];
        for (int i = 0; i <= segments; i++) {
            double[] p = points[Math.max(0, i - 1)], q = points[Math.min(segments, i + 1)];
            double dx = q[0] - p[0], dy = q[1] - p[1], dz = q[2] - p[2];
            double hx = -dz, hz = dx, hl = Math.sqrt(hx * hx + hz * hz);
            double[] flat = hl < 1.0E-6 ? new double[] { 1, 0, 0 } : new double[] { hx / hl, 0, hz / hl };
            double[] up = { dy * flat[2] - dz * flat[1], dz * flat[0] - dx * flat[2], dx * flat[1] - dy * flat[0] };
            double ul = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
            if (ul < 1.0E-9) {
                up = new double[] { 0, 1, 0 };
            } else {
                up = new double[] { up[0] / ul, up[1] / ul, up[2] / ul };
                if (up[1] < 0) {
                    up = new double[] { -up[0], -up[1], -up[2] };
                }
            }
            flats[i] = flat;
            ups[i] = up;
        }
        // Both sprites are one flat tone (nothing finer than a pixel, nothing to shimmer): sampled in their middle.
        float u0 = sprite.getU(0.25F), u1 = sprite.getU(0.75F), v0 = sprite.getV(0.25F), v1 = sprite.getV(0.75F);
        for (int i = 0; i < segments; i++) {
            // The four sides: up, flat, down, -flat, each the side's normal and its two edges either side of it.
            for (int side = 0; side < 4; side++) {
                double sn = side < 2 ? 1 : -1;
                double[] n0 = scaled(side % 2 == 0 ? ups[i] : flats[i], sn), n1 = scaled(side % 2 == 0 ? ups[i + 1] : flats[i + 1], sn);
                double[] e0 = side % 2 == 0 ? flats[i] : ups[i], e1 = side % 2 == 0 ? flats[i + 1] : ups[i + 1];
                double[] c0 = corner(points[i], n0, e0, half, 1), c1 = corner(points[i], n0, e0, half, -1);
                double[] c2 = corner(points[i + 1], n1, e1, half, -1), c3 = corner(points[i + 1], n1, e1, half, 1);
                face(pose, buffer, c0, c1, c2, c3, n0, u0, u1, v0, v1, light);
            }
        }
    }

    private static double[] scaled(double[] v, double s) {
        return new double[] { v[0] * s, v[1] * s, v[2] * s };
    }

    // A corner of the tube's square section: out along n by half, and half to one side (sign) along e.
    private static double[] corner(double[] p, double[] n, double[] e, double half, double sign) {
        return new double[] { p[0] + (n[0] + e[0] * sign) * half, p[1] + (n[1] + e[1] * sign) * half, p[2] + (n[2] + e[2] * sign) * half };
    }

    // One side of a segment, wound to face out along n (front faces are counter-clockwise seen from outside).
    private static void face(PoseStack.Pose pose, VertexConsumer buffer, double[] c0, double[] c1, double[] c2, double[] c3, double[] n, float u0,
            float u1, float v0, float v1, int light) {
        double ax = c1[0] - c0[0], ay = c1[1] - c0[1], az = c1[2] - c0[2], bx = c2[0] - c1[0], by = c2[1] - c1[1], bz = c2[2] - c1[2];
        double facing = (ay * bz - az * by) * n[0] + (az * bx - ax * bz) * n[1] + (ax * by - ay * bx) * n[2];
        float nx = (float) n[0], ny = (float) n[1], nz = (float) n[2];
        if (facing >= 0) {
            vertex(pose, buffer, c0, u0, v0, nx, ny, nz, light);
            vertex(pose, buffer, c1, u0, v1, nx, ny, nz, light);
            vertex(pose, buffer, c2, u1, v1, nx, ny, nz, light);
            vertex(pose, buffer, c3, u1, v0, nx, ny, nz, light);
        } else {
            vertex(pose, buffer, c3, u1, v0, nx, ny, nz, light);
            vertex(pose, buffer, c2, u1, v1, nx, ny, nz, light);
            vertex(pose, buffer, c1, u0, v1, nx, ny, nz, light);
            vertex(pose, buffer, c0, u0, v0, nx, ny, nz, light);
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, double[] p, float u, float v, float nx, float ny, float nz, int light) {
        buffer.addVertex(pose, (float) p[0], (float) p[1], (float) p[2])
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
