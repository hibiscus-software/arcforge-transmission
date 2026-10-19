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
    EHV_UNIT("ehv_unit_transformer", Kind.UNIT, VoltageTier.EHV, null, TowerMesh.Design.UNIT_EHV);

    // A tower carries a line; a substation transformer joins a line of its tier (primary) to one a tier down (secondary)
    // through an FE buffer, stepping down or up; a unit transformer joins a line to the FE block in front of it.
    public enum Kind {
        TOWER, SUBSTATION, UNIT
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
        return kind != Kind.TOWER;
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
        return this != LV && this != MV && this != HV;
    }

    public TowerMesh.Design design() {
        return design;
    }

    public int height() {
        return design.height;
    }
}
