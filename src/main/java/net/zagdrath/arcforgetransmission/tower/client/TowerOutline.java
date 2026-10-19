/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.zagdrath.arcforgetransmission.tower.TowerBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPartBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.TowerTier;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// A tower's outline: the insulator looked at, its own box; anywhere else, the whole frame (its poles, crossarms, peaks
// and lattice bays) as one outline, not block by block. Each model box is drawn turned with the structure (its true
// edges, at 45 degrees too), not as the blocks' stepped shapes.
@EventBusSubscriber(modid = net.zagdrath.arcforgetransmission.tower.TowerContent.MODID, value = Dist.CLIENT)
public final class TowerOutline {
    // The frame's edges per tier and orientation (x1 y1 z1 x2 y2 z2 each, blocks from the centre column's base), built once.
    private static final Map<Integer, float[]> FRAMES = new ConcurrentHashMap<>();

    private TowerOutline() {}

    @SubscribeEvent
    static void extract(ExtractBlockOutlineRenderStateEvent event) {
        BlockPos pos = event.getBlockPos();
        BlockState state = event.getBlockState();
        BlockPos main = state.getBlock() instanceof TowerBlock ? pos
                : state.getBlock() instanceof TowerPartBlock ? TowerPartBlock.mainOf(event.getLevel(), pos) : null;
        if (main == null || !(event.getLevel().getBlockState(main).getBlock() instanceof TowerBlock tower)) {
            return;
        }
        TowerTier tier = tower.tier();
        int eighths = TowerPlacement.eighths(event.getLevel().getBlockState(main));
        Vec3 hit = event.getHitResult().getLocation();
        List<AABB> boxes = TowerPlacement.insulatorBoxes(tier, eighths, main);
        int insulator = -1;
        for (int i = 0; i < boxes.size(); i++) {
            // The blocks' shapes are snapped out to whole pixels (TowerPlacement.shape), so a hit on an insulator can land
            // up to a pixel outside its exact box.
            if (boxes.get(i).inflate(1.0 / 16.0 + 1.0E-3).contains(hit)) {
                insulator = i;
                break;
            }
        }
        float[] edges = insulator >= 0 ? edges(List.of(TowerMesh.get(tier.design()).insulators().get(insulator)), eighths)
                : FRAMES.computeIfAbsent(tier.ordinal() * 8 + eighths, k -> edges(TowerPlacement.modelFrame(tier), k % 8));
        BlockPos centre = TowerPlacement.centre(tier, eighths, main);
        event.addCustomRenderer((renderState, collector, poseStack, levelRenderState) -> {
            GameRenderer renderer = Minecraft.getInstance().gameRenderer;
            Vec3 camera = levelRenderState.cameraRenderState.pos;
            boolean highContrast = renderState.highContrast();
            RenderType type = highContrast ? RenderTypes.linesDepthBias()
                    : renderer.useImprovedTransparency() ? RenderTypes.linesTranslucentNoDepthWrite() : RenderTypes.linesTranslucent();
            float width = renderer.gameRenderState().windowRenderState.appropriateLineWidth;
            poseStack.pushPose();
            poseStack.translate(centre.getX() - camera.x, centre.getY() - camera.y, centre.getZ() - camera.z);
            if (highContrast) {
                lines(collector, poseStack, RenderTypes.secondaryBlockOutline(), edges, -16777216, 7.0F);
            }
            lines(collector, poseStack, type, edges, highContrast ? -11010079 : ARGB.black(102), width);
            poseStack.popPose();
            return true;
        });
    }

    private static void lines(net.minecraft.client.renderer.SubmitNodeCollector collector, com.mojang.blaze3d.vertex.PoseStack poseStack,
            RenderType type, float[] edges, int colour, float width) {
        collector.submitCustomGeometry(poseStack, type, (pose, buffer) -> {
            Vector3f normal = new Vector3f();
            for (int i = 0; i < edges.length; i += 6) {
                normal.set(edges[i + 3] - edges[i], edges[i + 4] - edges[i + 1], edges[i + 5] - edges[i + 2]).normalize();
                buffer.addVertex(pose, edges[i], edges[i + 1], edges[i + 2]).setColor(colour).setNormal(pose, normal).setLineWidth(width);
                buffer.addVertex(pose, edges[i + 3], edges[i + 4], edges[i + 5]).setColor(colour).setNormal(pose, normal).setLineWidth(width);
            }
        });
    }

    // The 12 edges of each model box (px), turned by eighths: blocks from the centre column's base.
    private static float[] edges(List<float[]> boxes, int eighths) {
        List<float[]> out = new ArrayList<>();
        int[][] pairs = { { 0, 1 }, { 2, 3 }, { 4, 5 }, { 6, 7 }, { 0, 2 }, { 1, 3 }, { 4, 6 }, { 5, 7 }, { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 } };
        for (float[] b : boxes) {
            double[][] corner = new double[8][];
            for (int i = 0; i < 8; i++) {
                corner[i] = TowerMesh.turnedPoint8((i & 1) == 0 ? b[0] : b[3], (i & 4) == 0 ? b[1] : b[4], (i & 2) == 0 ? b[2] : b[5], eighths);
            }
            for (int[] p : pairs) {
                double[] a = corner[p[0]], c = corner[p[1]];
                out.add(new float[] { (float) a[0], (float) a[1], (float) a[2], (float) c[0], (float) c[1], (float) c[2] });
            }
        }
        float[] flat = new float[out.size() * 6];
        for (int i = 0; i < out.size(); i++) {
            System.arraycopy(out.get(i), 0, flat, i * 6, 6);
        }
        return flat;
    }
}
