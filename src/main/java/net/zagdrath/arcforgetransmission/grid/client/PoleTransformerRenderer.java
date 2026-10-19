/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.client;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforge.client.renderer.blockentity.MeshRendering;
import net.zagdrath.arcforge.client.renderer.blockentity.StructureRenderer;
import net.zagdrath.arcforgetransmission.grid.block.PoleTransformerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.client.TowerRenderer;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Draws a Pole-Mount Transformer (TowerMesh.poleTransformer() on the towers' sheet, turned to its facing like a tower)
// and its wires.
public class PoleTransformerRenderer implements StructureRenderer<PoleTransformerBlockEntity, PoleTransformerRenderer.State> {
    private static final float[] EMPTY = new float[0];
    private static final Map<Integer, float[]> TURNED = new ConcurrentHashMap<>();

    public static class State extends BlockEntityRenderState {
        public float[] solid = EMPTY;
        public @Nullable TextureAtlasSprite sheet;
        public int light;
        public List<WireRenderer.Prepared> wires = List.of();
    }

    public PoleTransformerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public void extractRenderState(PoleTransformerBlockEntity transformer, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        StructureRenderer.super.extractRenderState(transformer, state, partialTicks, cameraPosition, breakProgress);
        int turns = TowerPlacement.turns(transformer.facing());
        state.solid = TURNED.computeIfAbsent(turns, t -> TowerMesh.turned(TowerMesh.poleTransformer().solid(), t));
        state.sheet = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(TowerRenderer.SHEET);
        Level level = transformer.getLevel();
        state.light = level != null ? LightCoordsUtil.getLightCoords(level, transformer.getBlockPos()) : LightCoordsUtil.FULL_BRIGHT;
        state.wires = WireRenderer.prepare(transformer.wires(), transformer.getBlockPos(), level);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        TextureAtlasSprite sheet = state.sheet;
        if (sheet != null) {
            MeshRendering.submitStill(collector, poseStack, RenderTypes.entitySolid(sheet.atlasLocation()), state.solid, sheet, state.light);
        }
        WireRenderer.submit(state.wires, poseStack, collector);
    }

    @Override
    public AABB getRenderBoundingBox(PoleTransformerBlockEntity transformer) {
        return transformer.getRenderBox();
    }
}
