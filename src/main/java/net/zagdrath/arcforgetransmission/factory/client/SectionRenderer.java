/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory.client;

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
import net.zagdrath.arcforgetransmission.factory.SectionBlockEntity;
import net.zagdrath.arcforgetransmission.factory.SectionGeometry;
import net.zagdrath.arcforgetransmission.grid.client.WireRenderer;
import net.zagdrath.arcforgetransmission.tower.client.TowerRenderer;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.Op;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// Draws a lineup section (FactoryMesh.section on the towers' sheet) turned to face its way, each operator's handle and
// flag in its state (a breaker closed, open or tripped; an HV or EHV disconnector and earthing switch), and the wires
// strung to an incomer's bushings. Each combination of states is built once and kept.
public class SectionRenderer implements StructureRenderer<SectionBlockEntity, SectionRenderer.State> {
    private static final float[] EMPTY = new float[0];
    private static final Map<String, float[]> BUILT = new ConcurrentHashMap<>();

    public static class State extends BlockEntityRenderState {
        public float[] solid = EMPTY;
        public @Nullable TextureAtlasSprite sheet;
        public int light;
        public List<WireRenderer.Prepared> wires = List.of();
    }

    public SectionRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public int getViewDistance() {
        return 128;
    }

    // The section and its operators in these states, turned.
    public static float[] mesh(FactoryMesh.Section section, int tier, int turns, OpState[] states) {
        StringBuilder key = new StringBuilder(section + ":" + tier + ":" + turns);
        for (OpState s : states) {
            key.append(':').append(s.ordinal());
        }
        return BUILT.computeIfAbsent(key.toString(), k -> {
            float[] base = FactoryMesh.section(section, tier).solid();
            List<FactoryMesh.Operator> ops = FactoryMesh.operators(section, tier);
            int total = base.length;
            float[][] parts = new float[ops.size()][];
            for (int i = 0; i < ops.size(); i++) {
                parts[i] = FactoryMesh.operator(ops.get(i).op(), tier, i < states.length ? states[i] : OpState.OPEN).solid();
                total += parts[i].length;
            }
            float[] out = java.util.Arrays.copyOf(base, total);
            int at = base.length;
            for (int i = 0; i < ops.size(); i++) {
                FactoryMesh.Operator o = ops.get(i);
                float[] p = parts[i];
                for (int v = 0; v + 7 < p.length; v += 8) {
                    out[at + v] = p[v] + o.x() / 16;
                    out[at + v + 1] = p[v + 1] + o.y() / 16;
                    out[at + v + 2] = p[v + 2] + o.z() / 16;
                    System.arraycopy(p, v + 3, out, at + v + 3, 5);
                }
                at += p.length;
            }
            return TowerMesh.turned(out, turns);
        });
    }

    // Each operator's state, from the section's breakers, disconnector and earthing switch.
    public static OpState[] states(SectionBlockEntity section) {
        List<FactoryMesh.Operator> ops = FactoryMesh.operators(section.section(), section.tier().ordinal());
        OpState[] out = new OpState[ops.size()];
        int breaker = 0;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i).op();
            out[i] = switch (op) {
                case BREAKER, SWITCH -> breaker < section.breakers().size() ? section.breakers().get(breaker++).state() : OpState.OPEN;
                case DISCONNECTOR -> section.disconnector() ? OpState.CLOSED : OpState.OPEN;
                case EARTH -> section.earthed() ? OpState.CLOSED : OpState.OPEN;
            };
        }
        return out;
    }

    @Override
    public void extractRenderState(SectionBlockEntity section, State state, float partialTicks, Vec3 cameraPosition,
            ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        StructureRenderer.super.extractRenderState(section, state, partialTicks, cameraPosition, breakProgress);
        state.solid = mesh(section.section(), section.tier().ordinal(), SectionGeometry.turns(section.facing()), states(section));
        state.sheet = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getSprite(TowerRenderer.SHEET);
        Level level = section.getLevel();
        state.light = level != null ? LightCoordsUtil.getLightCoords(level, section.getBlockPos().above()) : LightCoordsUtil.FULL_BRIGHT;
        state.wires = WireRenderer.prepare(section.wires(), section.getBlockPos(), level);
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
    public AABB getRenderBoundingBox(SectionBlockEntity section) {
        return section.getRenderBox();
    }
}
