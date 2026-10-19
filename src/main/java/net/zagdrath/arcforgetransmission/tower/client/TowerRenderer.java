/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.client;

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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforge.client.renderer.blockentity.MeshRendering;
import net.zagdrath.arcforge.client.renderer.blockentity.StructureRenderer;
import net.zagdrath.arcforgetransmission.grid.client.WireRenderer;
import net.zagdrath.arcforgetransmission.tower.TowerBlockEntity;
import net.zagdrath.arcforgetransmission.tower.TowerContent;
import net.zagdrath.arcforgetransmission.tower.TowerPlacement;
import net.zagdrath.arcforgetransmission.tower.TowerTier;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Draws a transmission tower from its main block: the model (TowerMesh, on the sheet block/transmission_tower/towers),
// built once per tower and turned once per facing, drawn from its copy on the GPU and shaded like Arcforge's other
// large models (Arcforge's MeshRendering: each face as a block face turned its way is), and the wires strung from it
// (WireRenderer), and on a gantry its jumpers. Arcforge's StructureRenderer keeps it drawn while any of it is in view, not only while its main
// block's chunk section is.
public class TowerRenderer implements StructureRenderer<TowerBlockEntity, TowerRenderer.State> {
    public static final Identifier SHEET = Identifier.fromNamespaceAndPath(TowerContent.MODID, "block/transmission_tower/towers");
    private static final float[] EMPTY = new float[0];

    private record Key(TowerTier tier, int eighths, boolean alt) {}

    // Each tower's model turned to each facing: Arcforge's StaticMeshes keeps a still model on the GPU only for a pose
    // that doesn't turn, so the turn is in the vertices instead. The same array every time, so the GPU copy is shared.
    private static final Map<Key, float[]> TURNED = new ConcurrentHashMap<>();

    public static class State extends BlockEntityRenderState {
        public float[] solid = EMPTY;
        public float offsetX, offsetZ;
        public @Nullable TextureAtlasSprite sheet;
        public int light;
        public List<WireRenderer.Prepared> wires = List.of();
    }

    public TowerRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    // Towers are landmarks: drawn from further off than most block entities.
    @Override
    public int getViewDistance() {
        return 256;
    }

    private static float[] model(TowerTier tier, int eighths, boolean alt) {
        return TURNED.computeIfAbsent(new Key(tier, eighths, alt),
                key -> TowerMesh.turned8((alt ? TowerMesh.variant(tier.design()) : TowerMesh.get(tier.design())).solid(), eighths));
    }

    @Override
    public void extractRenderState(TowerBlockEntity tower, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        StructureRenderer.super.extractRenderState(tower, state, partialTicks, cameraPosition, breakProgress);
        TowerTier tier = tower.tier();
        int eighths = tower.eighths();
        state.solid = model(tier, eighths, tower.showsAlt());
        // The model's frame starts at the centre column's base block.
        BlockPos offset = TowerPlacement.mainOffset(tier, eighths);
        state.offsetX = -offset.getX();
        state.offsetZ = -offset.getZ();
        state.sheet = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(SHEET);
        Level level = tower.getLevel();
        // The light just above the centre column's foot.
        state.light = level != null ? LightCoordsUtil.getLightCoords(level, TowerPlacement.centre(tier, eighths, tower.getBlockPos()).above())
                : LightCoordsUtil.FULL_BRIGHT;
        List<net.zagdrath.arcforgetransmission.grid.world.WireView> wires = tower.wires();
        if (!tower.guys().isEmpty()) {
            wires = new java.util.ArrayList<>(wires);
            net.minecraft.world.phys.Vec3 top = tower.guyPoint();
            for (BlockPos guy : tower.guys()) {
                wires.add(new net.zagdrath.arcforgetransmission.grid.world.WireView(-1, top, net.minecraft.world.phys.Vec3.atCenterOf(guy).add(0, 0.5, 0),
                        tier.voltage(), net.zagdrath.arcforgetransmission.grid.world.WireView.GUY));
            }
        }
        if (tier.drawsJumpers()) {
            // Its jumpers: from each link's line-side insulator (the strain's clamp) to its yard side, turned with the tower.
            wires = new java.util.ArrayList<>(wires);
            var insulators = tower.insulators();
            for (int[] pair : tower.linkPairs()) {
                wires.add(new net.zagdrath.arcforgetransmission.grid.world.WireView(-1, insulators.get(pair[0]).at(), insulators.get(pair[1]).at(),
                        insulators.get(pair[0]).tier(), net.zagdrath.arcforgetransmission.grid.world.WireView.JUMPER));
            }
        }
        state.wires = WireRenderer.prepare(wires, tower.getBlockPos(), level);
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        WireRenderer.submit(state.wires, poseStack, collector);
        TextureAtlasSprite sheet = state.sheet;
        if (sheet == null) {
            return;
        }
        poseStack.pushPose();
        poseStack.translate(state.offsetX, 0.0F, state.offsetZ);
        MeshRendering.submitStill(collector, poseStack, RenderTypes.entitySolid(sheet.atlasLocation()), state.solid, sheet, state.light);
        poseStack.popPose();
    }

    @Override
    public AABB getRenderBoundingBox(TowerBlockEntity tower) {
        return tower.getRenderBox();
    }
}
