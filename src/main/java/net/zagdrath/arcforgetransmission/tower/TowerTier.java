/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower;

import net.zagdrath.arcforgetransmission.tower.model.TowerMesh;

// The four transmission towers, one per wire tier to come: the line voltage each will carry and the model it stands as
// (TowerMesh). Each is placed whole from its item (TowerItem); for now they're inert structures.
public enum TowerTier {
    LV("lv_distribution_pole", "LV", 128, TowerMesh.Design.DISTRIBUTION_POLE),
    MV("mv_sub_transmission_pole", "MV", 512, TowerMesh.Design.SUB_TRANSMISSION_POLE),
    HV("hv_h_frame_tower", "HV", 2_048, TowerMesh.Design.H_FRAME),
    EV("ev_lattice_tower", "EV", 8_192, TowerMesh.Design.LATTICE);

    private final String id, label;
    private final int volts;
    private final TowerMesh.Design design;

    TowerTier(String id, String label, int volts, TowerMesh.Design design) {
        this.id = id;
        this.label = label;
        this.volts = volts;
        this.design = design;
    }

    // The block and item id.
    public String id() {
        return id;
    }

    // LV, MV, HV or EV.
    public String label() {
        return label;
    }

    public int volts() {
        return volts;
    }

    public TowerMesh.Design design() {
        return design;
    }

    public int height() {
        return design.height;
    }
}
