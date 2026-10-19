/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.grid.world.WireView;

// A block entity with insulators wires attach to (a tower's main block, an inductor, a transformer). The grid's record is
// GridData's; this only says what insulators the block has (insulators()), keeps the grid told as it loads and goes, and
// mirrors the wires it draws (those strung from it) for its renderer.
public abstract class GridEndpointBlockEntity extends BlockEntity {
    private List<WireView> wires = List.of();
    private @Nullable AABB wireBox;
    private boolean wireBoxKnown;

    protected GridEndpointBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // The block's insulators, in a fixed order (an insulator's place in it is how the grid knows it).
    public abstract List<Insulator> insulators();

    // The insulator nearest a point (where a player clicked), or -1 if it has none.
    public int nearest(Vec3 point) {
        List<Insulator> insulators = insulators();
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < insulators.size(); i++) {
            double d = insulators.get(i).at().distanceToSqr(point);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    public List<WireView> wires() {
        return wires;
    }

    public void setWires(List<WireView> wires, boolean sync) {
        this.wires = List.copyOf(wires);
        wireBoxKnown = false;
        setChanged();
        if (sync && level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // The box the block's wires (sag and all) are drawn in, or null with none.
    public @Nullable AABB wireBox() {
        if (!wireBoxKnown) {
            AABB box = null;
            for (WireView wire : wires) {
                double[] mid = Catenary.point(new double[] { wire.from().x, wire.from().y, wire.from().z },
                        new double[] { wire.to().x, wire.to().y, wire.to().z }, 0.5);
                AABB span = new AABB(wire.from(), wire.to()).minmax(new AABB(mid[0], mid[1], mid[2], mid[0], mid[1], mid[2])).inflate(0.25);
                box = box == null ? span : box.minmax(span);
            }
            wireBox = box;
            wireBoxKnown = true;
        }
        return wireBox;
    }

    // The block's own render box, with its wires'.
    protected AABB withWires(AABB own) {
        AABB box = wireBox();
        return box == null ? own : own.minmax(box);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            GridManager.loaded(server, this);
            GridManager.attach(server, worldPosition, insulators(), this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel server) {
            GridManager.unloaded(server, this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level instanceof ServerLevel server) {
            GridManager.unloaded(server, this);
        }
    }

    // The block is going (broken, blown up, replaced): its wires go with it, dropping their coils here.
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel server) {
            GridManager.detach(server, pos, true);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("wires", WireView.LIST_CODEC, wires);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        wires = input.read("wires", WireView.LIST_CODEC).orElse(List.of());
        wireBoxKnown = false;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
