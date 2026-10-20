/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforge.client.renderer.blockentity.StructureRenderer;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.factory.CableNetwork;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlock;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlockEntity;

// Draws the cables lying in a tray block (CableMesh), each in its tier's jacket, and their free drops down to their
// terminations. Each block's tubes are worked out once for what it holds and kept.
public class CableTrayRenderer implements StructureRenderer<CableTrayBlockEntity, CableTrayRenderer.State> {
    private static final int CACHE = 4096;
    private static final Map<String, float[]> MESHES = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE;
        }
    };

    public static class State extends BlockEntityRenderState {
        public List<float[]> meshes = List.of();
        public List<Integer> tiers = List.of();
        public int light;
    }

    public CableTrayRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public int getViewDistance() {
        return 96;
    }

    public static Identifier jacket(int tier) {
        return Identifier.fromNamespaceAndPath(ArcforgeTransmission.MODID, "block/cable/" + new String[] { "lv", "mv", "hv", "ehv" }[Math.max(0, Math.min(3, tier))]);
    }

    @Override
    public void extractRenderState(CableTrayBlockEntity tray, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        StructureRenderer.super.extractRenderState(tray, state, partialTicks, cameraPosition, breakProgress);
        CableTrayBlock.Kind kind = tray.getBlockState().getBlock() instanceof CableTrayBlock block ? block.kind() : CableTrayBlock.Kind.STANDARD;
        List<float[]> meshes = new ArrayList<>();
        List<Integer> tiers = new ArrayList<>();
        for (CableNetwork.Lie lie : tray.lies()) {
            String key = kind + ":" + lie;
            float[] mesh;
            synchronized (MESHES) {
                mesh = MESHES.computeIfAbsent(key, k -> CableMesh.build(kind, List.of(lie)));
            }
            meshes.add(mesh);
            tiers.add(lie.tier());
        }
        state.meshes = meshes;
        state.tiers = tiers;
        Level level = tray.getLevel();
        state.light = level != null ? LightCoordsUtil.getLightCoords(level, tray.getBlockPos()) : LightCoordsUtil.FULL_BRIGHT;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.meshes.isEmpty()) {
            return;
        }
        submitTubes(state.meshes, state.tiers, state.light, poseStack, collector);
    }

    // Draws tubes (CableMesh quads, px) each in its tier's jacket.
    static void submitTubes(List<float[]> meshes, List<Integer> tiers, int light, PoseStack poseStack, SubmitNodeCollector collector) {
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        TextureAtlasSprite first = atlas.getSprite(jacket(0));
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(first.atlasLocation()), (pose, buffer) -> {
            for (int i = 0; i < meshes.size(); i++) {
                draw(pose, buffer, meshes.get(i), atlas.getSprite(jacket(tiers.get(i))), light);
            }
        });
    }

    private static void draw(PoseStack.Pose pose, VertexConsumer buffer, float[] mesh, TextureAtlasSprite sprite, int light) {
        for (int q = 0; q + CableMesh.QUAD <= mesh.length; q += CableMesh.QUAD) {
            float nx = mesh[q], ny = mesh[q + 1], nz = mesh[q + 2];
            for (int c = 0; c < 4; c++) {
                int at = q + 3 + c * 5;
                // Round the jacket its rows (two a facet, the stripe's on top); along it every column is alike (its middle's).
                float u = sprite.getU(0.5F), v = sprite.getV(Math.max(0, Math.min(1, mesh[at + 3] / 16.0F)));
                buffer.addVertex(pose, mesh[at] / 16.0F, mesh[at + 1] / 16.0F, mesh[at + 2] / 16.0F)
                        .setColor(0xFFFFFFFF)
                        .setUv(u, v)
                        .setOverlay(OverlayTexture.NO_OVERLAY)
                        .setLight(light)
                        .setNormal(pose, nx, ny, nz);
            }
        }
    }

    @Override
    public AABB getRenderBoundingBox(CableTrayBlockEntity tray) {
        return tray.getRenderBox();
    }
}
