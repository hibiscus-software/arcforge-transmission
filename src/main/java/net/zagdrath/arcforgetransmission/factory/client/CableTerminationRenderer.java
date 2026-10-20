/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory.client;

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
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlock;
import net.zagdrath.arcforgetransmission.factory.CableTerminationBlockEntity;
import net.zagdrath.arcforgetransmission.tower.client.TowerRenderer;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Draws a Cable Termination (FactoryMesh.termination on the towers' sheet): standing, on its wall bracket turned with its
// back to the wall, or hanging.
public class CableTerminationRenderer implements StructureRenderer<CableTerminationBlockEntity, CableTerminationRenderer.State> {
    private static final float[] EMPTY = new float[0];
    private static final Map<String, float[]> TURNED = new ConcurrentHashMap<>();

    public static class State extends BlockEntityRenderState {
        public float[] solid = EMPTY;
        public @Nullable TextureAtlasSprite sheet;
        public int light;
    }

    public CableTerminationRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    @Override
    public void extractRenderState(CableTerminationBlockEntity termination, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        StructureRenderer.super.extractRenderState(termination, state, partialTicks, cameraPosition, breakProgress);
        int tier = termination.tier().ordinal(), turns = CableTerminationBlock.turns(termination.facing());
        FactoryMesh.Mount mount = termination.mountKind();
        state.solid = TURNED.computeIfAbsent(tier + ":" + mount + ":" + turns, k -> TowerMesh.turned(FactoryMesh.termination(tier, mount).solid(), turns));
        state.sheet = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(TowerRenderer.SHEET);
        Level level = termination.getLevel();
        state.light = level != null ? LightCoordsUtil.getLightCoords(level, termination.getBlockPos()) : LightCoordsUtil.FULL_BRIGHT;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        TextureAtlasSprite sheet = state.sheet;
        if (sheet != null) {
            MeshRendering.submitStill(collector, poseStack, RenderTypes.entitySolid(sheet.atlasLocation()), state.solid, sheet, state.light);
        }
    }

    @Override
    public AABB getRenderBoundingBox(CableTerminationBlockEntity termination) {
        return termination.getRenderBox();
    }
}
