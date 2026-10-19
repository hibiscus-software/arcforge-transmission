/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforge.client.renderer.blockentity.StructureRenderer;
import net.zagdrath.arcforgetransmission.grid.block.PinInsulatorBlockEntity;

// A Pin Insulator's wires (the pin insulator itself is its block model). Drawn while any of them is in view.
public class WiresOnlyRenderer implements StructureRenderer<PinInsulatorBlockEntity, WiresOnlyRenderer.State> {
    public static class State extends BlockEntityRenderState {
        public List<WireRenderer.Prepared> wires = List.of();
    }

    public WiresOnlyRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public void extractRenderState(PinInsulatorBlockEntity pinInsulator, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        StructureRenderer.super.extractRenderState(pinInsulator, state, partialTicks, cameraPosition, breakProgress);
        state.wires = WireRenderer.prepare(pinInsulator.wires(), pinInsulator.getBlockPos(), pinInsulator.getLevel());
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        WireRenderer.submit(state.wires, poseStack, collector);
    }

    @Override
    public AABB getRenderBoundingBox(PinInsulatorBlockEntity pinInsulator) {
        return pinInsulator.getRenderBox();
    }
}
