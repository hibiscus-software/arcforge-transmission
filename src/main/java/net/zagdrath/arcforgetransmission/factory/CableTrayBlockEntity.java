/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;

// A tray block's view of the cables lying in it (CableNetwork.Lie: each one's tier, slot and the sides it comes and goes
// by, or its free drop's far end), for drawing them (CableTrayRenderer). The cables themselves are CableData's; this is
// kept for the client and refreshed as they change, and again as it loads.
public class CableTrayBlockEntity extends BlockEntity {
    private List<CableNetwork.Lie> lies = List.of();

    public CableTrayBlockEntity(BlockPos pos, BlockState state) {
        super(FactoryContent.TRAY_ENTITY.get(), pos, state);
    }

    public List<CableNetwork.Lie> lies() {
        return lies;
    }

    // Reads what lies in it now and tells the client.
    public void show(ServerLevel level) {
        List<CableNetwork.Lie> now = CableNetwork.lies(level, worldPosition);
        if (!now.equals(lies)) {
            lies = now;
            setChanged();
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            show(server);
        }
    }

    private static final int FIELDS = 7;

    private int[] packed() {
        int[] out = new int[lies.size() * FIELDS];
        for (int i = 0; i < lies.size(); i++) {
            CableNetwork.Lie l = lies.get(i);
            int at = i * FIELDS;
            out[at] = l.tier();
            out[at + 1] = l.slot();
            out[at + 2] = l.in();
            out[at + 3] = l.out();
            out[at + 4] = Float.floatToIntBits(l.dx());
            out[at + 5] = Float.floatToIntBits(l.dy());
            out[at + 6] = Float.floatToIntBits(l.dz());
        }
        return out;
    }

    private static List<CableNetwork.Lie> unpacked(int[] in) {
        List<CableNetwork.Lie> out = new ArrayList<>();
        for (int at = 0; at + FIELDS <= in.length; at += FIELDS) {
            out.add(new CableNetwork.Lie(in[at], in[at + 1], in[at + 2], in[at + 3], Float.intBitsToFloat(in[at + 4]), Float.intBitsToFloat(in[at + 5]),
                    Float.intBitsToFloat(in[at + 6])));
        }
        return out;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("lies", Codec.INT_STREAM, java.util.Arrays.stream(packed()));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read("lies", Codec.INT_STREAM).ifPresent(stream -> lies = unpacked(stream.toArray()));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Its block, and its cables' drops to their terminations.
    public AABB getRenderBox() {
        AABB box = new AABB(worldPosition);
        for (CableNetwork.Lie l : lies) {
            if (l.in() < 0 || l.out() < 0) {
                box = box.minmax(new AABB(worldPosition).move(l.dx() - 0.5, l.dy() - 0.5, l.dz() - 0.5));
            }
        }
        return box.inflate(0.25);
    }
}
