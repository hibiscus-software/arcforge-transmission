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
// insulator to the other. A wire is two crossed ribbons (one flat, one upright, like Immersive Engineering's) carrying
// the tier's wire sprite (block/wire/<tier>: u along the wire, v across it, its 16 rows the conductor's round profile)
// at the tier's width; the sprite is laid at 1:1 along the wire, a segment of at most half a block at a time, so it
// never stretches. The EV superconductor's core (rows 6 to 9) glows full-bright (block/wire/ev_wire_glow).
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
        double[] prev = Catenary.point(a, b, 0.0);
        for (int i = 1; i <= segments; i++) {
            double[] next = Catenary.point(a, b, (double) i / segments);
            double dx = next[0] - prev[0], dy = next[1] - prev[1], dz = next[2] - prev[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-6) {
                prev = next;
                continue;
            }
            // Across: flat (horizontal, square to the wire) and upright (square to both).
            double hx = -dz, hz = dx, hl = Math.sqrt(hx * hx + hz * hz);
            double[] flat = hl < 1.0E-6 ? new double[] { 1, 0, 0 } : new double[] { hx / hl, 0, hz / hl };
            double[] up = { dy * flat[2] - dz * flat[1], dz * flat[0] - dx * flat[2], dx * flat[1] - dy * flat[0] };
            double ul = Math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]);
            up = new double[] { up[0] / ul, up[1] / ul, up[2] / ul };
            // u: this segment's length along the sprite, from its left edge (at most half of it: 1:1).
            float u0 = sprite.getU(0.0F), u1 = sprite.getU((float) Math.min(1.0, len));
            float v0 = sprite.getV(0.0F), v1 = sprite.getV(1.0F);
            ribbon(pose, buffer, prev, next, flat, half, u0, u1, v0, v1, light);
            ribbon(pose, buffer, prev, next, up, half, u0, u1, v0, v1, light);
            prev = next;
        }
    }

    // A two-sided quad from p to q, `half` either side along `across`.
    private static void ribbon(PoseStack.Pose pose, VertexConsumer buffer, double[] p, double[] q, double[] across, double half, float u0, float u1,
            float v0, float v1, int light) {
        float[] a = { (float) (p[0] + across[0] * half), (float) (p[1] + across[1] * half), (float) (p[2] + across[2] * half) };
        float[] b = { (float) (p[0] - across[0] * half), (float) (p[1] - across[1] * half), (float) (p[2] - across[2] * half) };
        float[] c = { (float) (q[0] - across[0] * half), (float) (q[1] - across[1] * half), (float) (q[2] - across[2] * half) };
        float[] d = { (float) (q[0] + across[0] * half), (float) (q[1] + across[1] * half), (float) (q[2] + across[2] * half) };
        // Its normal: up-ish for lighting either way round.
        float nx = 0, ny = 1, nz = 0;
        vertex(pose, buffer, a, u0, v0, nx, ny, nz, light);
        vertex(pose, buffer, b, u0, v1, nx, ny, nz, light);
        vertex(pose, buffer, c, u1, v1, nx, ny, nz, light);
        vertex(pose, buffer, d, u1, v0, nx, ny, nz, light);
        vertex(pose, buffer, d, u1, v0, nx, ny, nz, light);
        vertex(pose, buffer, c, u1, v1, nx, ny, nz, light);
        vertex(pose, buffer, b, u0, v1, nx, ny, nz, light);
        vertex(pose, buffer, a, u0, v0, nx, ny, nz, light);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float[] p, float u, float v, float nx, float ny, float nz, int light) {
        buffer.addVertex(pose, p[0], p[1], p[2])
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
