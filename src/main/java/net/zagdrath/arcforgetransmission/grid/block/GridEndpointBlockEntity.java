/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.grid.Catenary;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.GridNode;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.GridPayloads;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;
import net.zagdrath.arcforgetransmission.grid.world.WireView;

// A block entity with insulators wires attach to (a tower's main block, a pin insulator, a transformer). The grid's record is
// GridData's; this only says what insulators the block has (insulators()), keeps the grid told as it loads and goes, and
// mirrors the wires it draws (those strung from it) for its renderer.
public abstract class GridEndpointBlockEntity extends BlockEntity {
    private List<WireView> wires = List.of();
    private @Nullable AABB wireBox;
    private boolean wireBoxKnown;
    // What the HUD shows per insulator (the client's copy, from the server's refreshDisplay): its label, how many wires it
    // has, and the most current on any of them (A). And what was last sent (server side), and when.
    private List<Phase> hudPhases = List.of();
    private int[] hudWires = new int[0];
    private int[] hudCentiamps = new int[0];
    // Each insulator's voltage, thousandths of its tier's (0 dead).
    private int[] hudPermille = new int[0];
    private @Nullable List<Phase> sentPhases;
    private int @Nullable [] sentWires, sentCentiamps, sentPermille;
    private long sentAt;

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

    // --- The HUD --------------------------------------------------------------------------------------------------

    // An insulator's label, wire count and current (A), as the client last heard.
    public @Nullable Phase hudPhase(int index) {
        return index >= 0 && index < hudPhases.size() ? hudPhases.get(index) : null;
    }

    public int hudWires(int index) {
        return index >= 0 && index < hudWires.length ? hudWires[index] : 0;
    }

    public double hudAmps(int index) {
        return index >= 0 && index < hudCentiamps.length ? hudCentiamps[index] / 100.0 : 0.0;
    }

    // An insulator's voltage, per unit of its tier's (0 when dead).
    public double hudVolts(int index) {
        return index >= 0 && index < hudPermille.length ? hudPermille[index] / 1000.0 : 0.0;
    }

    // Server: reads the insulators' labels, wires and currents (edgeAmps, the last tick's) from the grid, and tells the
    // client when they've changed: labels, wire counts and the subclass's own state at once (or when forced), currents at
    // most twice a second.
    public void refreshDisplay(ServerLevel level, Map<Long, Double> edgeAmps, GridManager.TickView view, boolean force) {
        GridData.Dimension dimension = GridData.of(level);
        List<Long> ids = dimension.nodesAt(worldPosition);
        List<Phase> phases = new ArrayList<>(ids.size());
        int[] counts = new int[ids.size()], centiamps = new int[ids.size()], permille = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            GridNode node = id < 0 ? null : dimension.graph().node(id);
            phases.add(node == null ? Phase.A : node.phase());
            if (node == null) {
                continue;
            }
            double most = 0;
            for (GridEdge edge : dimension.graph().edgesOf(id)) {
                if (!edge.isJumper()) {
                    counts[i]++;
                }
                most = Math.max(most, edgeAmps.getOrDefault(edge.id(), 0.0));
            }
            centiamps[i] = (int) Math.min(Integer.MAX_VALUE, Math.round(most * 100.0));
            permille[i] = node.phase().isNeutral() || node.phase() == Phase.G ? 0 : (int) Math.round(view.volts(dimension.graph(), id) * 1000.0);
        }
        hudPhases = phases;
        hudWires = counts;
        hudCentiamps = centiamps;
        hudPermille = permille;
        boolean changed = !phases.equals(sentPhases) || !java.util.Arrays.equals(counts, sentWires) || hudStateChanged();
        boolean live = !java.util.Arrays.equals(centiamps, sentCentiamps) || !java.util.Arrays.equals(permille, sentPermille) || hudLiveChanged();
        if (force || changed || live && level.getGameTime() - sentAt >= 10) {
            sentPhases = phases;
            sentWires = counts;
            sentCentiamps = centiamps;
            sentPermille = permille;
            sentAt = level.getGameTime();
            hudSent();
            GridPayloads.sendHud(level, this);
        }
    }

    // A subclass's own HUD state: whether it changed since last sent (at once / live, throttled), and that it was sent.
    protected boolean hudStateChanged() {
        return false;
    }

    protected boolean hudLiveChanged() {
        return false;
    }

    protected void hudSent() {}

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

    // The endpoints loaded on this client (none on a server): what the client's sounds and particles look through for
    // transformers and live wires near the player (TransformerHums, CoronaEffects).
    public static final java.util.Set<GridEndpointBlockEntity> CLIENT_LOADED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Whether any of its insulators was live, as its HUD was last sent.
    public boolean hudLive() {
        for (int permille : hudPermille) {
            if (permille > 0) {
                return true;
            }
        }
        return false;
    }

    // The wires it draws (strung from its insulators).
    public List<WireView> wireViews() {
        return wires;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            CLIENT_LOADED.add(this);
        }
        if (level instanceof ServerLevel server) {
            GridManager.loaded(server, this);
            GridManager.attach(server, worldPosition, insulators(), this);
            GridManager.display(server, this, true);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CLIENT_LOADED.remove(this);
        if (level instanceof ServerLevel server) {
            GridManager.unloaded(server, this);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        CLIENT_LOADED.remove(this);
        if (level instanceof ServerLevel server) {
            GridManager.unloaded(server, this);
        }
    }

    // The block is going (broken, blown up, replaced): its wires go with it, dropping their coils here (not when a
    // creative player broke it).
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel server) {
            GridManager.detach(server, pos, GridManager.dropsAt(server, pos));
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
        // A HUD update (GridPayloads.Hud) comes without the wires: they stay as they were.
        input.read("wires", WireView.LIST_CODEC).ifPresent(read -> {
            wires = read;
            wireBoxKnown = false;
        });
        input.read("hud_phases", Codec.STRING).ifPresent(text -> {
            List<Phase> phases = new ArrayList<>();
            for (String id : text.split(",")) {
                if (!id.isEmpty()) {
                    phases.add(Phase.byId(id));
                }
            }
            hudPhases = phases;
        });
        input.read("hud_wires", Codec.INT_STREAM).ifPresent(stream -> hudWires = stream.toArray());
        input.read("hud_centiamps", Codec.INT_STREAM).ifPresent(stream -> hudCentiamps = stream.toArray());
        input.read("hud_permille", Codec.INT_STREAM).ifPresent(stream -> hudPermille = stream.toArray());
    }

    // What the client gets: what's saved, and the HUD's numbers.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveCustomOnly(registries);
        StringBuilder phases = new StringBuilder();
        for (Phase phase : hudPhases) {
            phases.append(phases.isEmpty() ? "" : ",").append(phase.id());
        }
        tag.putString("hud_phases", phases.toString());
        tag.putIntArray("hud_wires", hudWires);
        tag.putIntArray("hud_centiamps", hudCentiamps);
        tag.putIntArray("hud_permille", hudPermille);
        return tag;
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
