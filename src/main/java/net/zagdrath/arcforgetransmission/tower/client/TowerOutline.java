/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.client;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.zagdrath.arcforgetransmission.tower.TowerBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPartBlock;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.TowerTier;

// A tower's outline: the insulator looked at, its own box; anywhere else, the whole frame (its poles, crossarms, peaks
// and lattice bays) as one outline, not block by block.
@EventBusSubscriber(modid = net.zagdrath.arcforgetransmission.tower.TowerContent.MODID, value = Dist.CLIENT)
public final class TowerOutline {
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
        Direction facing = event.getLevel().getBlockState(main).getValue(TowerBlock.FACING);
        Vec3 hit = event.getHitResult().getLocation();
        AABB insulator = null;
        for (AABB box : TowerPlacement.insulatorBoxes(tier, facing, main)) {
            // The blocks' shapes are snapped out to whole pixels (TowerPlacement.shape), so a hit on an insulator can land
            // up to a pixel outside its exact box.
            if (box.inflate(1.0 / 16.0 + 1.0E-3).contains(hit)) {
                insulator = box;
                break;
            }
        }
        VoxelShape shape = insulator != null ? relative(List.of(insulator), pos) : relative(TowerPlacement.frameBoxes(tier, facing, main), pos);
        if (shape == null) {
            return;
        }
        event.addCustomRenderer((renderState, collector, poseStack, levelRenderState) -> {
            GameRenderer renderer = Minecraft.getInstance().gameRenderer;
            Vec3 camera = levelRenderState.cameraRenderState.pos;
            boolean highContrast = renderState.highContrast();
            RenderType type = highContrast ? RenderTypes.linesDepthBias()
                    : renderer.useImprovedTransparency() ? RenderTypes.linesTranslucentNoDepthWrite() : RenderTypes.linesTranslucent();
            poseStack.pushPose();
            poseStack.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            if (highContrast) {
                collector.submitShapeOutline(poseStack, shape, RenderTypes.secondaryBlockOutline(), -16777216, 7.0F, renderState.isTranslucent());
            }
            collector.submitShapeOutline(poseStack, shape, type, highContrast ? -11010079 : ARGB.black(102),
                    renderer.gameRenderState().windowRenderState.appropriateLineWidth, renderState.isTranslucent());
            poseStack.popPose();
            return true;
        });
    }

    // World boxes as one shape relative to pos, or null with none.
    private static @Nullable VoxelShape relative(List<AABB> boxes, BlockPos pos) {
        VoxelShape shape = null;
        for (AABB box : boxes) {
            VoxelShape s = Shapes.create(box.move(-pos.getX(), -pos.getY(), -pos.getZ()));
            shape = shape == null ? s : Shapes.or(shape, s);
        }
        return shape;
    }
}
