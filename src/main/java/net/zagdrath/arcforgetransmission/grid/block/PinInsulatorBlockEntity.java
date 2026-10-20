/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.zagdrath.arcforgetransmission.ArcforgeTransmission;
import net.zagdrath.arcforgetransmission.grid.Phase;
import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.grid.data.AttachGroups;
import net.zagdrath.arcforgetransmission.grid.world.GridData;
import net.zagdrath.arcforgetransmission.grid.world.GridManager;
import net.zagdrath.arcforgetransmission.grid.world.Insulator;

// A Pin Insulator: one insulator, one conductor's end, mounted on a face of the block it serves. Every pin insulator on one block
// makes one port (a whole circuit: A, B and C, or a line and N); the block's FE goes in or out through it, at up to the
// tier's voltage times the current limit (MountedPort, the Cable Termination's too). Mode and limit are the port's:
// setting them on one pin insulator sets every pin insulator on that block.
//
// Foreign blocks (any mod's but this one) may feed or be fed only at LV. An MV, HV or EHV pin insulator switched on against
// one is an overvoltage: the block is broken, destroyed or blown up (GridConfig.OVERVOLTAGE).
public class PinInsulatorBlockEntity extends MountedPortBlockEntity {
    // Just placed (nothing loaded from a save): takes its port's settings from the pin insulators already there.
    private boolean fresh = true;

    public PinInsulatorBlockEntity(BlockPos pos, BlockState state) {
        super(GridContent.PIN_INSULATOR_ENTITY.get(), pos, state);
    }

    @Override
    public VoltageTier tier() {
        return getBlockState().getBlock() instanceof PinInsulatorBlock pinInsulator ? pinInsulator.tier() : VoltageTier.LV;
    }

    @Override
    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(PinInsulatorBlock.FACING) ? state.getValue(PinInsulatorBlock.FACING) : Direction.UP;
    }

    // How far the insulator stands off the mounting face (px), its top the wire's attach point.
    public static double height(VoltageTier tier) {
        return AttachGroups.get("pin_insulator_" + tier.id()).groups().get(0).terminals().get(0).pos()[1];
    }

    @Override
    public List<Insulator> insulators() {
        VoltageTier tier = tier();
        Direction facing = facing();
        double off = (height(tier) - 8.0) / 16.0;
        Vec3 at = Vec3.atCenterOf(worldPosition).add(facing.getStepX() * off, facing.getStepY() * off, facing.getStepZ() * off);
        return List.of(new Insulator(tier, Phase.A, at, 0, "pin"));
    }

    // Its port's mode and current limit (on a Wall Bushing, that it passes wires through).
    @Override
    public Component hudMode() {
        if (onWallBushing()) {
            return Component.translatable("hud." + ArcforgeTransmission.MODID + ".pin_insulator.bushing");
        }
        return super.hudMode();
    }

    // Its phase label (on the client, as last sent).
    public Phase phase() {
        Phase phase = hudPhase(0);
        return phase != null ? phase : Phase.A;
    }

    // Every loaded pin insulator on the same block as this one (this one included), in a fixed order.
    public List<PinInsulatorBlockEntity> siblings() {
        List<PinInsulatorBlockEntity> out = new ArrayList<>();
        Level level = getLevel();
        BlockPos mount = mountPos();
        if (level == null) {
            return List.of(this);
        }
        for (Direction direction : Direction.values()) {
            BlockPos at = mount.relative(direction);
            if (level.isLoaded(at) && level.getBlockEntity(at) instanceof PinInsulatorBlockEntity other && !other.isRemoved()
                    && other.mountPos().equals(mount)) {
                out.add(other);
            }
        }
        if (!out.contains(this)) {
            out.add(this);
        }
        out.sort((a, b) -> a.worldPosition.compareTo(b.worldPosition));
        return out;
    }

    // Sets the mode and limit of the whole port.
    @Override
    public void setForPort(PinInsulatorMode mode, int limitEighths) {
        for (PinInsulatorBlockEntity pinInsulator : siblings()) {
            pinInsulator.set(mode, limitEighths);
        }
    }

    @Override
    public void onLoad() {
        if (fresh && level != null && !level.isClientSide()) {
            for (PinInsulatorBlockEntity other : siblings()) {
                if (other != this) {
                    set(other.mode, other.limitEighths);
                    break;
                }
            }
        }
        fresh = false;
        super.onLoad();
        if (level instanceof ServerLevel server) {
            GridManager.rejoin(server, mountPos());
        }
    }

    // Off a Wall Bushing, the ones left on it are joined again without it.
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel server) {
            GridManager.rejoin(server, mountPos());
        }
    }

    // On a Wall Bushing it only passes wires through (GridManager.rejoin): no FE of its own.
    public boolean onWallBushing() {
        return level != null && level.getBlockState(mountPos()).getBlock() instanceof WallBushingBlock;
    }

    // --- The port ---------------------------------------------------------------------------------------------------

    @Override
    public void ports(ServerLevel level, GridData.Dimension dimension, List<GridManager.PortHandler> out) {
        List<PinInsulatorBlockEntity> siblings = siblings();
        // One port per block: made by its first pin insulator. None on a Wall Bushing.
        if (siblings.get(0) != this || onWallBushing()) {
            return;
        }
        List<Long> nodes = new ArrayList<>();
        for (PinInsulatorBlockEntity pinInsulator : siblings) {
            Long id = dimension.nodeAt(new GridData.Anchor(pinInsulator.worldPosition, 0));
            if (id != null) {
                nodes.add(id);
            }
        }
        MountedPort.make(level, dimension, siblings, tier(), nodes, mountPos(), facing(), mount, mode, limitAmps(), out);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        fresh = false;
    }

    public AABB getRenderBox() {
        return withWires(new AABB(worldPosition));
    }
}
