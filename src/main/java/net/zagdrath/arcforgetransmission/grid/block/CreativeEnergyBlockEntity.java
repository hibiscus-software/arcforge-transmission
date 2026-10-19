/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import org.jspecify.annotations.Nullable;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;

// A Creative Source or Sink: gives (or takes and destroys) up to its setpoint of FE each tick, through any face, for
// testing a tier's lines. What a tick has moved follows transactions, so asking (a simulated move) uses none of it.
public class CreativeEnergyBlockEntity extends BlockEntity {
    private int setpoint;
    private long tick = Long.MIN_VALUE;
    private int moved;
    private long total;
    private final Handler handler = new Handler();

    public CreativeEnergyBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.CREATIVE_ENERGY_ENTITY.get(), pos, state);
        setpoint = defaultSetpoint();
    }

    public VoltageTier tier() {
        return getBlockState().getBlock() instanceof CreativeEnergyBlock creative ? creative.tier() : VoltageTier.LV;
    }

    public boolean isSource() {
        return getBlockState().getBlock() instanceof CreativeEnergyBlock creative && creative.isSource();
    }

    // The tier's rated power to begin with.
    private int defaultSetpoint() {
        return (int) tier().defaultMaxPower();
    }

    // The most it can be set to: four times the tier's rated power (enough to burn a wire out).
    public int maxSetpoint() {
        return (int) Math.min(Integer.MAX_VALUE, 4L * tier().defaultMaxPower());
    }

    public int setpoint() {
        return setpoint;
    }

    public long total() {
        return total;
    }

    // Doubles (or halves) the setpoint, from 16 FE/t up to the most; 0 below 16.
    public int step(boolean up) {
        if (up) {
            setpoint = setpoint <= 0 ? 16 : (int) Math.min(maxSetpoint(), (long) setpoint * 2);
        } else {
            setpoint = setpoint <= 16 ? 0 : setpoint / 2;
        }
        setChanged();
        return setpoint;
    }

    public @Nullable EnergyHandler energy(@Nullable Direction side) {
        return handler;
    }

    private int room() {
        long now = level == null ? 0 : level.getGameTime();
        if (now != tick) {
            tick = now;
            moved = 0;
        }
        return Math.max(0, setpoint - moved);
    }

    private final class Handler extends SnapshotJournal<long[]> implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return isSource() ? setpoint : 0;
        }

        @Override
        public long getCapacityAsLong() {
            return Math.max(1, setpoint);
        }

        @Override
        public int insert(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            if (isSource() || amount == 0) {
                return 0;
            }
            return take(amount, transaction);
        }

        @Override
        public int extract(int amount, TransactionContext transaction) {
            TransferPreconditions.checkNonNegative(amount);
            if (!isSource() || amount == 0) {
                return 0;
            }
            return take(amount, transaction);
        }

        private int take(int amount, TransactionContext transaction) {
            int n = Math.min(amount, room());
            if (n > 0) {
                updateSnapshots(transaction);
                moved += n;
                total += n;
            }
            return n;
        }

        @Override
        protected long[] createSnapshot() {
            return new long[] { moved, total, tick };
        }

        @Override
        protected void revertToSnapshot(long[] snapshot) {
            moved = (int) snapshot[0];
            total = snapshot[1];
            tick = snapshot[2];
        }

        @Override
        protected void onRootCommit(long[] originalState) {
            setChanged();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("setpoint", Codec.INT, setpoint);
        output.store("total", Codec.LONG, total);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        setpoint = Math.max(0, input.read("setpoint", Codec.INT).orElse(defaultSetpoint()));
        total = input.read("total", Codec.LONG).orElse(0L);
    }
}
