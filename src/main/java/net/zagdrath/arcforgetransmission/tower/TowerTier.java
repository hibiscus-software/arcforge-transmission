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
    SERVICE_METER("service_meter", Kind.SERVICE, VoltageTier.LV, null, TowerMesh.Design.SERVICE);

    // A tower carries a line; a substation transformer joins a line of its tier (primary) to one a tier down (secondary)
    // through an FE buffer, stepping down or up; a unit transformer joins a line to the FE block in front of it. The
    // protection devices join each line-side conductor to its load-side one while closed: a fuse cutout (one conductor;
    // blows on overcurrent), a recloser or circuit breaker (three; trips and recloses), a disconnect switch (three; by
    // hand). A dead-end pole ends lines both ways, each conductor jumpered through. A service meter feeds a building's
    // FE block from a split-phase service drop, counting what it delivers.
    public enum Kind {
        TOWER, SUBSTATION, UNIT, FUSE, BREAKER, DISCONNECT, DEAD_END, SERVICE
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
        return kind == Kind.FUSE || kind == Kind.BREAKER || kind == Kind.DISCONNECT;
    }

    // Whether its groups come in pairs joined through it (devices while closed, dead-ends always).
    public boolean linked() {
        return isDevice() || kind == Kind.DEAD_END;
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
        return this != LV && this != MV && this != HV && this != LV_DEAD_END && this != MV_DEAD_END;
    }

    public TowerMesh.Design design() {
        return design;
    }

    public int height() {
        return design.height;
    }
}
