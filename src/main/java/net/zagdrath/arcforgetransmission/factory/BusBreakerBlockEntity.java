/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.effects.ArcEvent;
import net.zagdrath.arcforgetransmission.grid.FlowSolver;
import net.zagdrath.arcforgetransmission.grid.GridEdge;
import net.zagdrath.arcforgetransmission.grid.world.GridArcs;
import net.zagdrath.arcforgetransmission.grid.world.GridConfig;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.tower.model.FactoryMesh.OpState;

// A busway piece with a breaker in it (a tap-off, a reducer): its two groups (in and out) joined while the breaker is
// closed, a protective link the grid grades with every other (so a tap trips before the feeder upstream, and that before
// its main). Its long-time pickup is its frame; tripped, it's reset by hand before it closes again.
public abstract class BusBreakerBlockEntity extends BusPieceBlockEntity {
    protected final SectionBreaker breaker = new SectionBreaker();
    private double load;

    protected BusBreakerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected int groups() {
        return 2;
    }

    public SectionBreaker breaker() {
        return breaker;
    }

    // Its frame, A.
    public abstract double frame();

    private static List<int[]> pairs() {
        return List.of(new int[] { 0, 3 }, new int[] { 1, 4 }, new int[] { 2, 5 });
    }

    protected void relink(ServerLevel level) {
        GridManager.link(level, worldPosition, pairs(), breaker.closed());
        GridManager.display(level, this, true);
    }

    private List<Long> linkEdges(GridData.Dimension dimension) {
        List<Long> out = new ArrayList<>();
        List<Long> a = nodes(dimension, 0), b = nodes(dimension, 1);
        for (int k = 0; k < Math.min(a.size(), b.size()); k++) {
            for (GridEdge edge : dimension.graph().edgesOf(a.get(k))) {
                if (edge.isJumper() && edge.other(a.get(k)) == b.get(k)) {
                    out.add(edge.id());
                }
            }
        }
        return out;
    }

    // An empty hand: closes it (open), opens it (closed), resets it (tripped).
    public Component operate(ServerLevel level) {
        String key = switch (breaker.state()) {
            case CLOSED -> "opened";
            case TRIPPED -> "reset";
            default -> "closed";
        };
        breaker.setState(breaker.state() == OpState.OPEN ? OpState.CLOSED : OpState.OPEN);
        changed(level);
        level.playSound(null, worldPosition, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.5F, 1.4F);
        return Component.translatable("message." + ArcforgeTransmission.MODID + ".lineup." + key);
    }

    // Its state changed: its link, its look and its save follow.
    protected void changed(ServerLevel level) {
        setChanged();
        relink(level);
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            relink(server);
        }
    }

    @Override
    public void protective(ServerLevel level, GridData.Dimension dimension, Map<Long, FlowSolver.Protective> out) {
        if (breaker.closed()) {
            FlowSolver.Protective p = new FlowSolver.Protective(breaker.pickup(frame()), false);
            for (long edge : linkEdges(dimension)) {
                out.put(edge, p);
            }
        }
    }

    @Override
    public void afterTick(ServerLevel level, GridData.Dimension dimension, GridManager.TickView view) {
        double most = 0;
        FlowSolver.Beyond beyond = FlowSolver.Beyond.NONE;
        for (long edge : linkEdges(dimension)) {
            double amps = view.amps(edge);
            if (amps > most) {
                most = amps;
                beyond = view.beyond(edge);
            }
        }
        double pickup = breaker.pickup(frame());
        load = pickup > 0 ? most / pickup : 0;
        if (breaker.tickBreaker(most, beyond, frame(), GridConfig.FEEDER_DIAL.getAsDouble(), GridConfig.BREAKER_TRIP_TICKS.getAsInt(),
                GridConfig.GRADING_INTERVAL.getAsInt(), GridConfig.FUSE_MELT.getAsDouble())) {
            GridArcs.arc(level, Vec3.atCenterOf(worldPosition), ArcEvent.Kind.BREAKER, tier(), pickup <= 0 ? 1 : most / pickup);
            changed(level);
        }
    }

    // Its load: its current over its pickup, last tick.
    public double load() {
        return load;
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("breaker", Codec.INT_STREAM, java.util.Arrays.stream(breaker.save()));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read("breaker", Codec.INT_STREAM).ifPresent(stream -> breaker.load(stream.toArray()));
    }
}
