/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import org.jspecify.annotations.Nullable;

import net.zagdrath.arcforgetransmission.grid.VoltageTier;
import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// The structures placed whole from an item (TowerItem) and drawn from their main block (TowerRenderer, TowerMesh): the
// four transmission towers, one per wire tier; the three substation transformers between neighbouring tiers; and the
// four unit transformers, a generator's or a machine's link to a line of its tier. Each its line voltage (the higher
// side's, for a substation), the model it stands as, and what it does (Kind).
public enum TowerTier {
    LV("lv_distribution_pole", Kind.TOWER, VoltageTier.LV, null, TowerMesh.Design.DISTRIBUTION_POLE),
    MV("mv_sub_transmission_pole", Kind.TOWER, VoltageTier.MV, null, TowerMesh.Design.SUB_TRANSMISSION_POLE),
    HV("hv_h_frame_tower", Kind.TOWER, VoltageTier.HV, null, TowerMesh.Design.H_FRAME),
    EHV("ehv_lattice_tower", Kind.TOWER, VoltageTier.EHV, null, TowerMesh.Design.LATTICE),
    MV_SUBSTATION("mv_substation_transformer", Kind.SUBSTATION, VoltageTier.MV, VoltageTier.LV, TowerMesh.Design.SUBSTATION_MV),
    HV_SUBSTATION("hv_substation_transformer", Kind.SUBSTATION, VoltageTier.HV, VoltageTier.MV, TowerMesh.Design.SUBSTATION_HV),
    EHV_SUBSTATION("ehv_substation_transformer", Kind.SUBSTATION, VoltageTier.EHV, VoltageTier.HV, TowerMesh.Design.SUBSTATION_EHV),
    LV_UNIT("lv_unit_transformer", Kind.UNIT, VoltageTier.LV, null, TowerMesh.Design.UNIT_LV),
    MV_UNIT("mv_unit_transformer", Kind.UNIT, VoltageTier.MV, null, TowerMesh.Design.UNIT_MV),
    HV_UNIT("hv_unit_transformer", Kind.UNIT, VoltageTier.HV, null, TowerMesh.Design.UNIT_HV),
    EHV_UNIT("ehv_unit_transformer", Kind.UNIT, VoltageTier.EHV, null, TowerMesh.Design.UNIT_EHV),
    LV_FUSE("lv_fuse_cutout", Kind.FUSE, VoltageTier.LV, null, TowerMesh.Design.FUSE_LV),
    MV_FUSE("mv_fuse_cutout", Kind.FUSE, VoltageTier.MV, null, TowerMesh.Design.FUSE_MV),
    LV_RECLOSER("lv_recloser", Kind.BREAKER, VoltageTier.LV, null, TowerMesh.Design.BREAKER_LV),
    MV_RECLOSER("mv_recloser", Kind.BREAKER, VoltageTier.MV, null, TowerMesh.Design.BREAKER_MV),
    HV_BREAKER("hv_circuit_breaker", Kind.BREAKER, VoltageTier.HV, null, TowerMesh.Design.BREAKER_HV),
    EHV_BREAKER("ehv_circuit_breaker", Kind.BREAKER, VoltageTier.EHV, null, TowerMesh.Design.BREAKER_EHV),
    LV_DISCONNECT("lv_disconnect_switch", Kind.DISCONNECT, VoltageTier.LV, null, TowerMesh.Design.DISCONNECT_LV),
    MV_DISCONNECT("mv_disconnect_switch", Kind.DISCONNECT, VoltageTier.MV, null, TowerMesh.Design.DISCONNECT_MV),
    HV_DISCONNECT("hv_disconnect_switch", Kind.DISCONNECT, VoltageTier.HV, null, TowerMesh.Design.DISCONNECT_HV),
    EHV_DISCONNECT("ehv_disconnect_switch", Kind.DISCONNECT, VoltageTier.EHV, null, TowerMesh.Design.DISCONNECT_EHV),
    LV_DEAD_END("lv_dead_end_pole", Kind.DEAD_END, VoltageTier.LV, null, TowerMesh.Design.DEAD_END_LV),
    MV_DEAD_END("mv_dead_end_pole", Kind.DEAD_END, VoltageTier.MV, null, TowerMesh.Design.DEAD_END_MV),
    SERVICE_METER("service_meter", Kind.SERVICE, VoltageTier.LV, null, TowerMesh.Design.SERVICE),
    HV_BUS("hv_bus", Kind.BUS, VoltageTier.HV, null, TowerMesh.Design.BUS_HV),
    EHV_BUS("ehv_bus", Kind.BUS, VoltageTier.EHV, null, TowerMesh.Design.BUS_EHV),
    HV_GANTRY("hv_dead_end_gantry", Kind.DEAD_END, VoltageTier.HV, null, TowerMesh.Design.GANTRY_HV),
    EHV_GANTRY("ehv_dead_end_gantry", Kind.DEAD_END, VoltageTier.EHV, null, TowerMesh.Design.GANTRY_EHV),
    MV_REGULATOR("mv_voltage_regulator", Kind.REGULATOR, VoltageTier.MV, null, TowerMesh.Design.REGULATOR_MV),
    HV_REGULATOR("hv_voltage_regulator", Kind.REGULATOR, VoltageTier.HV, null, TowerMesh.Design.REGULATOR_HV),
    RELAY_PANEL("relay_panel", Kind.RELAY, VoltageTier.LV, null, TowerMesh.Design.RELAY_PANEL),
    MV_GROUND_SWITCH("mv_ground_switch", Kind.GROUND, VoltageTier.MV, null, TowerMesh.Design.GROUND_SWITCH_MV),
    HV_GROUND_SWITCH("hv_ground_switch", Kind.GROUND, VoltageTier.HV, null, TowerMesh.Design.GROUND_SWITCH_HV),
    EHV_GROUND_SWITCH("ehv_ground_switch", Kind.GROUND, VoltageTier.EHV, null, TowerMesh.Design.GROUND_SWITCH_EHV),
    MV_CAPACITOR("mv_capacitor_bank", Kind.SHUNT, VoltageTier.MV, null, TowerMesh.Design.CAPACITOR_MV),
    HV_CAPACITOR("hv_capacitor_bank", Kind.SHUNT, VoltageTier.HV, null, TowerMesh.Design.CAPACITOR_HV),
    HV_REACTOR("hv_shunt_reactor", Kind.SHUNT, VoltageTier.HV, null, TowerMesh.Design.REACTOR_HV),
    EHV_REACTOR("ehv_shunt_reactor", Kind.SHUNT, VoltageTier.EHV, null, TowerMesh.Design.REACTOR_EHV),
    HV_DEAD_END_TOWER("hv_dead_end_tower", Kind.DEAD_END, VoltageTier.HV, null, TowerMesh.Design.DEAD_END_HV),
    EHV_DEAD_END_TOWER("ehv_dead_end_tower", Kind.DEAD_END, VoltageTier.EHV, null, TowerMesh.Design.DEAD_END_EHV),
    HV_TRANSPOSITION("hv_transposition_tower", Kind.DEAD_END, VoltageTier.HV, null, TowerMesh.Design.TRANSPOSITION_HV),
    EHV_TRANSPOSITION("ehv_transposition_tower", Kind.DEAD_END, VoltageTier.EHV, null, TowerMesh.Design.TRANSPOSITION_EHV),
    LV_RISER("lv_cable_riser", Kind.DEAD_END, VoltageTier.LV, null, TowerMesh.Design.RISER_LV),
    MV_RISER("mv_cable_riser", Kind.DEAD_END, VoltageTier.MV, null, TowerMesh.Design.RISER_MV),
    HV_RISER("hv_cable_riser", Kind.DEAD_END, VoltageTier.HV, null, TowerMesh.Design.RISER_HV),
    EHV_RISER("ehv_cable_riser", Kind.DEAD_END, VoltageTier.EHV, null, TowerMesh.Design.RISER_EHV),
    MV_SECTIONALIZER("mv_sectionalizer", Kind.SECTIONALIZER, VoltageTier.MV, null, TowerMesh.Design.SECTIONALIZER_MV),
    LV_ATS("lv_transfer_switch", Kind.ATS, VoltageTier.LV, null, TowerMesh.Design.ATS_LV),
    MV_ATS("mv_transfer_switch", Kind.ATS, VoltageTier.MV, null, TowerMesh.Design.ATS_MV);

    // A tower carries a line; a substation transformer joins a line of its tier (primary) to one a tier down (secondary),
    // an electrical link holding no energy that passes power either way; a unit transformer joins a line to the FE blocks
    // touching its front face (and, on Input, whatever pushes FE into it). The
    // protection devices join each line-side conductor to its load-side one while closed: a fuse cutout (one conductor;
    // blows on overcurrent), a recloser or circuit breaker (three; trips and recloses), a disconnect switch (three; by
    // hand). A dead-end pole ends lines both ways, each conductor jumpered through. A service meter feeds a building's
    // FE block from a split-phase service drop, counting what it delivers.
    //
    // Phase 4: a bus section (rigid pipe bus, one node a phase, joining the sections in line with it); a voltage
    // regulator (its source side joined to its load side through its boost); a relay panel (no insulators: it protects
    // the breakers it's linked to); a ground switch (grounds its line while closed); a shunt (a capacitor bank or
    // reactor, switched in and out); a sectionalizer (counts the recloser's operations, opens in the dead time); an
    // automatic transfer switch (its load joined to its preferred feeder or its alternate). Dead-end gantries, dead-end
    // and transposition towers and cable risers are dead-ends: each conductor joined through.
    public enum Kind {
        TOWER, SUBSTATION, UNIT, FUSE, BREAKER, DISCONNECT, DEAD_END, SERVICE, BUS, REGULATOR, RELAY, GROUND, SHUNT, SECTIONALIZER, ATS
    }

    private final String id;
    private final Kind kind;
    private final VoltageTier voltage;
    private final @Nullable VoltageTier secondary;
    private final TowerMesh.Design design;

    TowerTier(String id, Kind kind, VoltageTier voltage, @Nullable VoltageTier secondary, TowerMesh.Design design) {
        this.id = id;
        this.kind = kind;
        this.voltage = voltage;
        this.secondary = secondary;
        this.design = design;
    }

    // The block and item id.
    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isTransformer() {
        return kind == Kind.SUBSTATION || kind == Kind.UNIT;
    }

    // A fuse cutout, recloser / breaker or disconnect switch: its line side joined to its load side while closed.
    public boolean isDevice() {
        return kind == Kind.FUSE || kind == Kind.BREAKER || kind == Kind.DISCONNECT || kind == Kind.SECTIONALIZER;
    }

    // Whether its groups come in pairs joined through it (devices while closed, dead-ends and regulators always, a
    // transfer switch its load to one feeder).
    public boolean linked() {
        return isDevice() || kind == Kind.DEAD_END || kind == Kind.REGULATOR || kind == Kind.ATS;
    }

    // A protective device: one whose links count toward grading (the device nearest the trouble trips first).
    public boolean protective() {
        return kind == Kind.FUSE || kind == Kind.BREAKER;
    }

    // Whether it has an open and a closed state it's drawn in (the model's other state while open; a ground switch's and
    // a shunt's while out, a transfer switch's while on its alternate).
    public boolean switched() {
        return isDevice() || kind == Kind.GROUND || kind == Kind.SHUNT || kind == Kind.ATS;
    }

    // A shunt reactor (not a capacitor bank).
    public boolean reactor() {
        return this == HV_REACTOR || this == EHV_REACTOR;
    }

    // Whether the Wrench on Rotate turns it (everything placed but the lines' own towers and poles).
    public boolean rotatable() {
        return kind != Kind.TOWER && kind != Kind.DEAD_END;
    }

    // Whether it serves the FE block in front of it (so it only ever faces square on).
    public boolean servesBlock() {
        return kind == Kind.UNIT || kind == Kind.SERVICE;
    }

    // Its line's tier (a substation's primary, the higher side).
    public VoltageTier voltage() {
        return voltage;
    }

    // A substation's secondary tier (one down), or null.
    public @Nullable VoltageTier secondary() {
        return secondary;
    }

    // LV, MV, HV or EHV.
    public String label() {
        return voltage.name();
    }

    public int volts() {
        return voltage.volts();
    }

    // Galvanized steel (a pickaxe takes it, steel particles), not treated wood.
    public boolean steel() {
        return switch (this) {
            case LV, MV, HV, LV_DEAD_END, MV_DEAD_END, HV_DEAD_END_TOWER, HV_TRANSPOSITION, LV_RISER, MV_RISER -> false;
            default -> true;
        };
    }

    // Whether its renderer draws its links' jumpers as hanging conductors (WireView.JUMPER), from each link pair's first
    // insulator to its second, rather than the model carrying them: the gantries.
    public boolean drawsJumpers() {
        return design == TowerMesh.Design.GANTRY_HV || design == TowerMesh.Design.GANTRY_EHV;
    }

    public TowerMesh.Design design() {
        return design;
    }

    public int height() {
        return design.height;
    }
}
