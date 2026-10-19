/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// The four transmission towers' models (TowerRenderer draws them; TowerPlacement takes their cells and shapes from
// them), after real power-line structures: the LV Distribution Pole (a 12-34.5 kV wood pole), the MV Sub-Transmission
// Pole (69 kV), the HV H-Frame Tower (138 kV) and the EHV Lattice Tower (345 kV double circuit). For now they're inert
// structures; wires between their insulators come later.
//
// They're large models (Arcforge's docs/TEXTURE_STYLE.md, "Large model texturing" and "Transmission towers") on one
// sheet, block/transmission_tower/towers (tools/transmission_tower_texture_gen.py; keep the two layouts in step): every
// face at 16 texels per block, never stretched; no light painted across anything (the game's face shading lights it);
// every member and plate shading itself from its own edge strips (lit top and left, dark bottom and right) over its own
// window of a big non-repeating grain field (galvanized spangle, treated timber, concrete), picked from where the face
// is; seams only where parts really join (a lattice member ends at its panel point, a can's lid at its flange). The
// wood poles carry their own unrolled skin (a smooth ramp across and soft grain up it, like a vanilla log's side);
// insulators and the discs carry stepped ramps round them, the discs a ramp from rim to
// cap as well. No two faces facing the same way share a plane where both show (no z-fighting), and every part is
// closed where it can be seen.
//
// The frame: px, x across the line (the crossarms run along x), y up from the base block's bottom, u (z) along the
// line, the line running toward -u. The origin is the centre column's base block's minimum corner, so the tower stands
// on blocks round (0, 0, 0).
public final class TowerMesh extends MeshBuilder {
    public static final int SHEET_W = 256, SHEET_H = 512;

    // The four designs, by tier: height in blocks, the cell the main block sits in (from the centre column's base).
    public enum Design {
        DISTRIBUTION_POLE(10, 0, 0),
        SUB_TRANSMISSION_POLE(15, 0, 0),
        H_FRAME(21, -2, 0),
        LATTICE(32, -2, -2),
        SUBSTATION_MV(3, 0, 0),
        SUBSTATION_HV(4, 0, 0),
        SUBSTATION_EHV(5, 0, 0),
        UNIT_LV(1, 0, 0),
        UNIT_MV(2, 0, 0),
        UNIT_HV(2, 0, 0),
        UNIT_EHV(3, 0, 0),
        FUSE_LV(1, 0, 0),
        FUSE_MV(1, 0, 0),
        BREAKER_LV(2, 0, 0),
        BREAKER_MV(2, 0, 0),
        BREAKER_HV(3, 0, 0),
        BREAKER_EHV(3, 0, 0),
        DISCONNECT_LV(1, 0, 0),
        DISCONNECT_MV(2, 0, 0),
        DISCONNECT_HV(2, 0, 0),
        DISCONNECT_EHV(3, 0, 0),
        DEAD_END_LV(10, 0, 0),
        DEAD_END_MV(15, 0, 0),
        SERVICE(2, 0, 0),
        BUS_HV(4, 0, 0),
        BUS_EHV(5, 0, 0),
        GANTRY_HV(5, -2, 0),
        GANTRY_EHV(7, -3, 0),
        REGULATOR_MV(2, 0, 0),
        REGULATOR_HV(3, 0, 0),
        RELAY_PANEL(2, 0, 0),
        GROUND_SWITCH_MV(2, 0, 0),
        GROUND_SWITCH_HV(2, 0, 0),
        GROUND_SWITCH_EHV(3, 0, 0),
        CAPACITOR_MV(3, 0, 0),
        CAPACITOR_HV(4, 0, 0),
        REACTOR_HV(4, 0, 0),
        REACTOR_EHV(5, 0, 0),
        DEAD_END_HV(21, -2, 0),
        DEAD_END_EHV(32, -2, -2),
        TRANSPOSITION_HV(21, -2, 0),
        TRANSPOSITION_EHV(32, -2, -2),
        RISER_LV(10, 0, 0),
        RISER_MV(15, 0, 0),
        RISER_HV(4, 0, 0),
        RISER_EHV(5, 0, 0),
        SECTIONALIZER_MV(2, 0, 0),
        ATS_LV(2, 0, 0),
        ATS_MV(2, 0, 0);

        // A Phase 4 design's tier (0 LV to 3 EHV), by its name's suffix.
        public int tier() {
            String n = name();
            return n.endsWith("_EHV") ? 3 : n.endsWith("_HV") ? 2 : n.endsWith("_MV") ? 1 : 0;
        }

        // The tier (0 LV to 3 EHV) of a device design.
        public int deviceTier() {
            return switch (this) {
                case FUSE_LV, BREAKER_LV, DISCONNECT_LV -> 0;
                case FUSE_MV, BREAKER_MV, DISCONNECT_MV -> 1;
                case BREAKER_HV, DISCONNECT_HV -> 2;
                default -> 3;
            };
        }

        public final int height, mainX, mainZ;

        Design(int height, int mainX, int mainZ) {
            this.height = height;
            this.mainX = mainX;
            this.mainZ = mainZ;
        }
    }

    // A built tower: its quads, its solid boxes (px, the model's every part), the boxes its blocks are shaped from (px,
    // for collision, hits and outlines: its frame as uniform boxes, a pole, crossarm, peak or lattice bay each, and each
    // insulator its own), its insulators' boxes (px, in the order they're built) and the
    // block cells the shapes reach into (from the centre column's base block).
    public record Built(float[] solid, List<float[]> boxes, List<float[]> shapes, List<float[]> insulators, List<int[]> cells) {}

    // --- The sheet (tools/transmission_tower_texture_gen.py) ---

    // A plated material: its grain field, its lit and dark edge rows (1 px tall, 128 long) and columns (1 px wide, 128
    // tall), and the swatch for the corners where a lit edge meets a dark one.
    enum Mat {
        GALV(48, 0, 128, 128, 176, 177, 48, 49, 4),
        WOOD(48, 128, 128, 48, 178, 179, 50, 51, 14),
        CONCRETE(176, 0, 64, 32, 180, 181, 52, 53, 46);

        final int fx, fy, fw, fh, litRow, darkRow, litCol, darkCol, corner;

        Mat(int fx, int fy, int fw, int fh, int litRow, int darkRow, int litCol, int darkCol, int corner) {
            this.fx = fx;
            this.fy = fy;
            this.fw = fw;
            this.fh = fh;
            this.litRow = litRow;
            this.darkRow = darkRow;
            this.litCol = litCol;
            this.darkCol = darkCol;
            this.corner = corner;
        }
    }

    static final int EDGE_X = 48, EDGE_COL_Y = 192, EDGE_LENGTH = 128;

    // The ramps round round parts (periods 2 to 14 side by side from x 64, at x = (p - 1)p/2 - 1), each a block 16 rows
    // tall with every row alike, so a facet shows it at 1:1 down its length.
    enum Ramp {
        GALV(256), PORCELAIN(272), GLASS(288), CAN(304), COPPER(320);

        final int row;

        Ramp(int row) {
            this.row = row;
        }
    }

    static final int RAMP_X = 64, RAMP_H = 16;
    // The pole skins: LV (16 wide, the whole way round) and MV / HV (ten 3 px facets), 512 tall.
    static final int SKIN_LV_X = 0, SKIN_LV_W = 16, SKIN_MV_X = 16, SKIN_MV_W = 30, SKIN_H = 512;
    // Disc tops, cap (top row) to rim, 8 x 8.
    static final int DISC_PORCELAIN_X = 176, DISC_GLASS_X = 184, DISC_Y = 32;
    // Modules: the danger sign, the end grain, the pole tag and the transformer lid (the Pole-Mount Transformer's).
    static final int SIGN_X = 176, SIGN_Y = 40, GRAIN_X = 192, GRAIN_Y = 40, TAG_X = 208, TAG_Y = 40, LID_X = 212, LID_Y = 40;

    // Flat swatches (4x4 from (64, 224), 32 a row), in the generator's order.
    static final int GALV0 = 0, WOOD0 = 10, PORC0 = 19, GLASS0 = 26, CAN0 = 33, COPPER0 = 40, CONC0 = 44, ALU0 = 50, HAZARD = 53, BLACK = 54,
            RED = 55, MARKER = 56;

    private static final float[] UP = { 0, 1, 0 };

    private final List<float[]> boxes = new ArrayList<>();
    // The uniform frame boxes (poles, crossarms, peaks, the lattice's bays) and every insulator's box.
    private final List<float[]> frame = new ArrayList<>();
    private final List<float[]> insulators = new ArrayList<>();
    // Where each face's grain window comes from: a running count, mixed with where the face is.
    private long faceSeed;

    private TowerMesh() {
        super(SHEET_W, SHEET_H);
    }

    private static final Map<Design, Built> BUILT = Collections.synchronizedMap(new EnumMap<>(Design.class));
    private static final Map<Design, Built> VARIANTS = Collections.synchronizedMap(new EnumMap<>(Design.class));

    // The tower, built once.
    public static Built get(Design design) {
        return BUILT.computeIfAbsent(design, TowerMesh::build);
    }

    // The model in its other state, for drawing: a switch or breaker open, a fuse blown (the same shapes as get()).
    public static Built variant(Design design) {
        return VARIANTS.computeIfAbsent(design, d -> build(d, true));
    }

    private static Built build(Design design) {
        return build(design, false);
    }

    private static Built build(Design design, boolean alt) {
        TowerMesh mesh = new TowerMesh();
        switch (design) {
            case DISTRIBUTION_POLE -> mesh.distributionPole();
            case SUB_TRANSMISSION_POLE -> mesh.subTransmissionPole();
            case H_FRAME -> mesh.hFrame();
            case LATTICE -> mesh.lattice(0);
            case SUBSTATION_MV, SUBSTATION_HV, SUBSTATION_EHV -> mesh.substation(Substation.of(design));
            case UNIT_LV, UNIT_MV, UNIT_HV, UNIT_EHV -> mesh.unit(Unit.of(design));
            case FUSE_LV, FUSE_MV -> mesh.fuse(design.deviceTier(), alt);
            case BREAKER_LV, BREAKER_MV, BREAKER_HV, BREAKER_EHV -> mesh.breaker(design.deviceTier(), alt);
            case DISCONNECT_LV, DISCONNECT_MV, DISCONNECT_HV, DISCONNECT_EHV -> mesh.disconnect(design.deviceTier(), alt);
            case DEAD_END_LV -> mesh.deadEndLv();
            case DEAD_END_MV -> mesh.deadEndMv();
            case SERVICE -> mesh.service();
            case BUS_HV, BUS_EHV -> mesh.bus(design.tier());
            case GANTRY_HV, GANTRY_EHV -> mesh.gantry(design.tier());
            case REGULATOR_MV, REGULATOR_HV -> mesh.regulator(design.tier());
            case RELAY_PANEL -> mesh.relayPanel();
            case GROUND_SWITCH_MV, GROUND_SWITCH_HV, GROUND_SWITCH_EHV -> mesh.groundSwitch(design.tier(), alt);
            case CAPACITOR_MV, CAPACITOR_HV -> mesh.capacitorBank(design.tier());
            case REACTOR_HV, REACTOR_EHV -> mesh.reactor(design.tier());
            case DEAD_END_HV -> mesh.deadEndHv(false);
            case TRANSPOSITION_HV -> mesh.deadEndHv(true);
            case DEAD_END_EHV -> mesh.lattice(1);
            case TRANSPOSITION_EHV -> mesh.lattice(2);
            case RISER_LV -> mesh.riserLv();
            case RISER_MV -> mesh.riserMv();
            case RISER_HV, RISER_EHV -> mesh.riserStand(design.tier());
            case SECTIONALIZER_MV -> mesh.sectionalizer(alt);
            case ATS_LV, ATS_MV -> mesh.ats(design.tier(), alt);
        }
        List<float[]> shapes = new ArrayList<>(mesh.frame);
        shapes.addAll(mesh.insulators);
        return new Built(mesh.solid.toArray(), List.copyOf(mesh.boxes), List.copyOf(shapes), List.copyOf(mesh.insulators), cells(shapes));
    }

    // The block cells (from the centre column's base block) the boxes reach into by more than a sliver.
    private static List<int[]> cells(List<float[]> boxes) {
        Set<Long> seen = new LinkedHashSet<>();
        List<int[]> cells = new ArrayList<>();
        float e = 0.05F;
        for (float[] b : boxes) {
            for (int x = (int) Math.floor((b[0] + e) / PX); x <= (int) Math.floor((b[3] - e) / PX); x++) {
                for (int y = (int) Math.floor((b[1] + e) / PX); y <= (int) Math.floor((b[4] - e) / PX); y++) {
                    for (int z = (int) Math.floor((b[2] + e) / PX); z <= (int) Math.floor((b[5] - e) / PX); z++) {
                        if (y < 0) {
                            continue;
                        }
                        long key = ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
                        if (seen.add(key)) {
                            cells.add(new int[] { x, y, z });
                        }
                    }
                }
            }
        }
        return cells;
    }

    // ================================================================================================================
    // The Pole-Mount Transformer
    // ================================================================================================================

    // Where the transformer's can stands in its block (px, front north, the pole to the south): the same can the LV pole
    // used to carry, one block tall, hung 1 px off the pole's face (its back reaches into the pole's block).
    public static final float CAN_X = 8.0F, CAN_Z = 15.4F, CAN_Y0 = 0.0F, CAN_Y1 = 16.0F;
    // The primaries' x (A, B, C, west to east), the secondaries' (L1, N, L2), and their heights.
    public static final float[] PRIMARY_X = { 5.2F, 8.0F, 10.8F }, SECONDARY_X = { 5.0F, 8.0F, 11.0F };
    public static final float PRIMARY_TOP = CAN_Y1 - 0.8F + 3.2F, SECONDARY_Y = 6.0F;
    // The faces the secondaries stand off: the can's north facet, and their tips.
    public static final float SECONDARY_TIP_Z = 7.9F, NEUTRAL_TIP_Z = 8.9F;
    // Where its pole's surface is when it hangs on an LV pole (the next block south, its 8 facets of 2 px).
    private static final float POLE_FACE_Z = 16.0F + 8.0F - 2.41F;
    private static Built transformer, transformerMv;
    // The MV can's primaries: taller porcelain with three sheds, their tops (the attach points) at the lid + 5.6.
    public static final float MV_PRIMARY_TOP = CAN_Y1 - 0.8F + 5.6F;

    // The Pole-Mount Transformer, built once: px in its block's frame, front north; the hanger brackets reach south
    // into the pole's block.
    public static synchronized Built poleTransformer() {
        if (transformer == null) {
            TowerMesh mesh = new TowerMesh();
            mesh.buildPoleTransformer(false);
            transformer = new Built(mesh.solid.toArray(), List.copyOf(mesh.boxes), List.copyOf(mesh.boxes), List.of(), cells(mesh.boxes));
        }
        return transformer;
    }

    // The MV Distribution Transformer: the same can and secondary, its primaries MV porcelain (taller, three sheds).
    public static synchronized Built poleTransformerMv() {
        if (transformerMv == null) {
            TowerMesh mesh = new TowerMesh();
            mesh.buildPoleTransformer(true);
            transformerMv = new Built(mesh.solid.toArray(), List.copyOf(mesh.boxes), List.copyOf(mesh.boxes), List.of(), cells(mesh.boxes));
        }
        return transformerMv;
    }

    private void buildPoleTransformer(boolean mv) {
        float r = can(CAN_X, CAN_Z, CAN_Y0, CAN_Y1);
        // Two hanger brackets from inside the pole to inside the can, as on the LV pole.
        for (float y : new float[] { 5, 13 }) {
            member(new float[] { CAN_X, y, POLE_FACE_Z + 0.6F }, new float[] { CAN_X, y, CAN_Z + r - 1.2F }, 3.0F, 1.0F, UP, Mat.GALV, true);
        }
        for (float x : PRIMARY_X) {
            if (mv) {
                float y = CAN_Y1 - 0.8F;
                turned(x, CAN_Z, new float[][] { { y, 0.9F }, { y + 1.0F, 0.9F }, { y + 1.0F, 1.3F }, { y + 1.6F, 1.3F }, { y + 1.6F, 0.9F },
                        { y + 2.4F, 0.9F }, { y + 2.4F, 1.3F }, { y + 3.0F, 1.3F }, { y + 3.0F, 0.9F }, { y + 3.8F, 0.9F }, { y + 3.8F, 1.3F },
                        { y + 4.4F, 1.3F }, { y + 4.4F, 0.9F }, { y + 5.0F, 0.9F } }, 6, Ramp.PORCELAIN, true, false);
                turned(x, CAN_Z, new float[][] { { y + 5.0F, 0.6F }, { MV_PRIMARY_TOP, 0.6F } }, 6, Ramp.GALV, true, false);
            } else {
                primaryBushing(x, CAN_Y1 - 0.8F, CAN_Z);
            }
        }
        // The secondary on the can's north facet: L1 and L2 porcelain bushings standing off it with a terminal stud,
        // the neutral a bare stud with a nut (bonded to the can).
        float face = CAN_Z - r * (float) Math.cos(Math.PI / 8);
        float y = SECONDARY_Y;
        for (int i = 0; i < 3; i++) {
            float x = SECONDARY_X[i];
            if (i == 1) {
                swatchBox(x - 0.5F, y - 0.5F, NEUTRAL_TIP_Z + 0.6F, x + 0.5F, y + 0.5F, face + 0.4F, GALV0 + 6);
                swatchBox(x - 0.9F, y - 0.9F, NEUTRAL_TIP_Z, x + 0.9F, y + 0.9F, NEUTRAL_TIP_Z + 0.6F, GALV0 + 8);
            } else {
                swatchBox(x - 0.8F, y - 0.8F, face - 1.6F, x + 0.8F, y + 0.8F, face + 0.4F, PORC0 + 3);
                swatchBox(x - 1.3F, y - 1.3F, face - 1.3F, x + 1.3F, y + 1.3F, face - 0.8F, PORC0 + 4);
                swatchBox(x - 0.45F, y - 0.45F, SECONDARY_TIP_Z, x + 0.45F, y + 0.45F, face - 1.6F, GALV0 + 7);
            }
        }
    }


    // ================================================================================================================
    // The towers
    // ================================================================================================================

    // The LV pole's neutral: its pin insulator's foot, on a standoff off the pole's west face, 2 blocks under the
    // crossarm (tools/tower_attach_groups_gen.py reads the same numbers).
    public static final float NEUTRAL_X = 2.2F, NEUTRAL_Y = 100.0F;

    // LV Distribution Pole, 10 blocks: a treated pole (8 facets of 2 px), one crossarm with three pin insulators near
    // the top, the neutral's pin insulator on a standoff bracket below it, a ground wire stapled down the east side and a pole tag
    // facing the line's south end. No transformer: the Pole-Mount Transformer is its own block, hung on a pole where
    // one is wanted.
    private void distributionPole() {
        float cx = 8, cz = 8, top = 150;
        pole(cx, cz, 0, top, 8, 2.0F, false, 140);
        // The crossarm, against the pole's south face.
        float armY = 132, armZ0 = 10.0F, armZ1 = 13.0F;
        timber(-12, armY, armZ0, 28, armY + 4, armZ1);
        // Through-bolt, and a flat V brace from one bolt on the pole's south face out to the arm's underside.
        swatchBox(7.5F, armY + 1.5F, 7.0F, 8.5F, armY + 2.5F, armZ1 + 0.6F, GALV0 + 7);
        vBrace(cx, armY - 18, 10.7F, armY, 11.5F, 10.0F, 1.2F, 0.6F, true, true);
        for (float x : new float[] { -8, 1, 24 }) {
            pinInsulator(x, armY + 4, (armZ0 + armZ1) / 2);
        }
        // The neutral, as on a real distribution pole: a pin insulator on a galvanized standoff bracket off the pole's
        // west face, two blocks below the crossarm (to the side, so its wire runs clear of the pole along the line).
        float ny = NEUTRAL_Y, nx = NEUTRAL_X;
        swatchBox(nx - 1.2F, ny - 1.4F, cz - 0.7F, cx - 1.0F, ny, cz + 0.7F, GALV0 + 6);
        swatchBox(cx - 2.41F - 0.4F, ny - 3.4F, cz - 1.0F, cx - 2.41F + 0.2F, ny + 0.6F, cz + 1.0F, GALV0 + 7);
        pinInsulator(nx, ny, cz);
        // The ground wire down the east face, stapled every 12 px.
        float gx = cx + 2.41F + 0.45F;
        swatchBox(gx - 0.45F, 2, cz - 0.45F, gx + 0.45F, top - 2, cz + 0.45F, COPPER0 + 2);
        for (float y = 8; y < top - 4; y += 12) {
            swatchBox(gx - 0.2F, y, cz - 1.4F, gx + 0.8F, y + 0.6F, cz + 1.4F, GALV0 + 7);
        }
        // The pole tag, on the south face at eye height in a ground-level view.
        tag(cx, 40, cz + 2.41F, true);
    }

    // MV Sub-Transmission Pole, 15 blocks: a thicker treated pole (8 facets of 3 px), two crossarms (the lower wider)
    // with porcelain post insulators, a steel brace under each arm, and a shield wire peak on top.
    private void subTransmissionPole() {
        float cx = 8, cz = 8, top = 222;
        pole(cx, cz, 0, top, 8, 3.0F, true, 260);
        float face = cz + 3.62F;
        float upperY = 196, lowerY = 166;
        // The upper arm reaches out west only (phase A); the lower, wider arm both ways (B and C).
        timber(-12, upperY, face - 0.4F, 14, upperY + 4, face + 3.6F);
        timber(-20, lowerY, face - 0.4F, 36, lowerY + 4, face + 3.6F);
        float az = face + 1.6F;
        for (float armY : new float[] { upperY, lowerY }) {
            swatchBox(7.4F, armY + 1.4F, cz - 4.2F, 8.6F, armY + 2.6F, face + 4.2F, GALV0 + 7);
        }
        // The crossarm braces: steel, from one bolt on the pole's south face to the arms' undersides.
        vBrace(cx, upperY - 20, face + 0.6F, upperY, face + 1.6F, 14.0F, 1.6F, 0.8F, true, false);
        vBrace(cx, lowerY - 20, face + 0.6F, lowerY, face + 1.6F, 16.0F, 1.6F, 0.8F, true, true);
        postInsulator(-8, upperY + 4, az, 12);
        postInsulator(-16, lowerY + 4, az, 12);
        postInsulator(32, lowerY + 4, az, 12);
        shieldPeak(cx, top, cz, 16);
    }

    // HV H-Frame Tower, 21 blocks, 5 wide: two treated poles 4 blocks apart (10 facets of 3 px), a heavy crossbeam near
    // the top with three suspension strings (one between the poles, one outside each), a galvanized X-brace between the
    // poles, and a shield wire peak on each pole top.
    private void hFrame() {
        float cz = 8, top = 316, lx = -24, rx = 40;
        pole(lx, cz, 0, top, 10, 3.0F, true, 40);
        pole(rx, cz, 0, top, 10, 3.0F, true, 150);
        float r = 4.85F;
        // The crossbeam on the poles' south faces, through-bolted, with a steel knee brace under it at each pole.
        float beamY = 282, bz0 = cz + r - 0.5F, bz1 = bz0 + 6.0F;
        timber(-48, beamY, bz0, 64, beamY + 7, bz1);
        for (float px : new float[] { lx, rx }) {
            swatchBox(px - 0.7F, beamY + 2.5F, cz - r - 0.6F, px + 0.7F, beamY + 4.0F, bz1 + 0.6F, GALV0 + 7);
            vBrace(px, beamY - 24, cz + r + 0.4F, beamY, bz0 + 1.6F, 14.0F, 1.8F, 1.0F, true, true);
        }
        float sz = (bz0 + bz1) / 2.0F;
        suspension(-42, beamY, sz, 8);
        suspension(8, beamY, sz, 8);
        suspension(58, beamY, sz, 8);
        // The X-brace on the poles' north faces: four half-members from bolts sunk in the poles to a gusset where they
        // cross, with a band round each pole at its ends.
        float xz = cz - r + 1.4F, y0 = 140, y1 = 250;
        float[] mid = { (lx + rx) / 2.0F, (y0 + y1) / 2.0F, xz };
        float[] depth = { 0, 0, 1 };
        for (float[] end : new float[][] { { lx + 2.2F, y0 }, { lx + 2.2F, y1 }, { rx - 2.2F, y0 }, { rx - 2.2F, y1 } }) {
            float[] from = { end[0], end[1], xz };
            // Each half stops just inside the gusset, so no two of them overlap.
            float[] to = add(mid, scale(normalize(sub(from, mid)), 1.6F));
            member(from, to, 2.0F, 2.0F, depth, Mat.GALV, true);
        }
        plateBox(mid[0] - 2.0F, mid[1] - 2.0F, xz - 1.5F, mid[0] + 2.0F, mid[1] + 2.0F, xz + 1.5F, Mat.GALV);
        for (float px : new float[] { lx, rx }) {
            for (float y : new float[] { y0, y1 }) {
                turned(px, cz, new float[][] { { y - 2.0F, r - 0.2F }, { y - 2.0F, r + 0.5F }, { y + 2.0F, r + 0.5F }, { y + 2.0F, r - 0.2F } }, 10, Ramp.GALV,
                        false, false);
            }
        }
        shieldPeak(lx, top, cz, 18);
        shieldPeak(rx, top, cz, 18);
    }

    // EHV Lattice Tower, 32 blocks: four galvanized corner legs on stepped concrete footings, battered to a waist at the
    // lowest crossarms and straight above; X-bracing in every bay with girts at its panel points and plan bracing at the
    // arm levels; three crossarm levels each side (double circuit, the middle arms longest) with a suspension string at
    // each tip; a shield wire peak; an anti-climb guard of barbed bars; step bolts up the north-west leg; and a danger
    // sign on the south face.
    // mode 0: the suspension tower; 1: dead-end (a strain string each way at each arm tip, jumpered under it); 2:
    // transposition (the same, the jumpers crossing to the next arm on their side: lower to middle, middle to top, top
    // to lower).
    private void lattice(int mode) {
        float c = 8;
        float[] levels = { 8, 56, 104, 152, 200, 248, 304, 336, 368, 400, 432, 464 };
        // Footings, base plates and the legs, a member per bay (spliced at each panel point).
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float fx = c + sx * 36, fz = c + sz * 36;
                concrete(fx - 8, 0, fz - 8, fx + 8, 3, fz + 8);
                concrete(fx - 6, 3, fz - 6, fx + 6, 8, fz + 6);
                plateBox(fx - 3.5F, 8, fz - 3.5F, fx + 3.5F, 9, fz + 3.5F, Mat.GALV);
                for (int k = 0; k + 1 < levels.length; k++) {
                    member(legAt(sx, sz, levels[k]), legAt(sx, sz, levels[k + 1]), 3.0F, 3.0F, new float[] { sx, 0, sz }, Mat.GALV, true);
                }
            }
        }
        // Girts round every panel point, and an X in each face of every bay, joined where it crosses.
        for (int k = 0; k < levels.length; k++) {
            float y = levels[k];
            for (int face = 0; face < 4; face++) {
                float[][] ends = faceEnds(face, y);
                if (k > 0) {
                    // Girts end at the legs' faces, so two meeting at a corner never overlap.
                    brace(ends[0], ends[1], 1.1F, 2.0F, 2.0F, faceNormal(face), true);
                }
                if (k + 1 < levels.length) {
                    float[][] upper = faceEnds(face, levels[k + 1]);
                    float[] mid = midpoint(midpoint(ends[0], upper[1]), midpoint(ends[1], upper[0]));
                    // The bracing is a little lighter than the girts (1.6 px), so where they meet at a panel point no
                    // two faces share a plane; the second diagonal runs just inside the first.
                    float[] in = scale(faceNormal(face), -1.8F);
                    member(ends[0], mid, 1.6F, 1.6F, faceNormal(face), Mat.GALV, false);
                    member(mid, upper[1], 1.6F, 1.6F, faceNormal(face), Mat.GALV, false);
                    member(add(ends[1], in), add(mid, in), 1.6F, 1.6F, faceNormal(face), Mat.GALV, false);
                    member(add(mid, in), add(upper[0], in), 1.6F, 1.6F, faceNormal(face), Mat.GALV, false);
                }
            }
        }
        // Splice plates round each leg at the waist, where the battered legs turn upright.
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                float[] p = legAt(sx, sz, 304);
                plateBox(p[0] - 2.4F, 301.5F, p[2] - 2.4F, p[0] + 2.4F, 306.5F, p[2] + 2.4F, Mat.GALV);
            }
        }
        // Plan bracing across the body at the waist and each arm level.
        for (float y : new float[] { 304, 368, 432, 464 }) {
            member(legAt(-1, -1, y), legAt(1, 1, y), 1.6F, 1.6F, UP, Mat.GALV, false);
            member(add(legAt(1, -1, y), new float[] { 0, -1.8F, 0 }), add(legAt(-1, 1, y), new float[] { 0, -1.8F, 0 }), 1.6F, 1.6F, UP, Mat.GALV, false);
        }
        // The crossarms: lower (reach 64 px from the tower's middle), middle (80, the longest) and top (56), each side.
        float[][] arms = { { 304, 64 }, { 368, 80 }, { 432, 56 } };
        for (int side = -1; side <= 1; side += 2) {
            float[][] tips = new float[3][];
            for (int k = 0; k < 3; k++) {
                float[] level = arms[k];
                float tipX = arm(side, level[0], level[1]);
                if (mode == 0) {
                    suspension(tipX, level[0] - 4.0F, c, 13);
                } else {
                    float y = level[0] - 2.0F;
                    float n = glassStrain(tipX, y, c - DEAD_END_EHV_FACE, -1, DEAD_END_EHV_DISCS, DEAD_END_EHV_RIM);
                    float so = glassStrain(tipX, y, c + DEAD_END_EHV_FACE, 1, DEAD_END_EHV_DISCS, DEAD_END_EHV_RIM);
                    tips[k] = new float[] { tipX, y, n, so };
                }
            }
            for (int k = 0; mode > 0 && k < 3; k++) {
                float[] from = tips[k], to = tips[mode == 2 ? (k + 1) % 3 : k];
                float drop = Math.min(from[1], to[1]) - 14;
                jumper(new float[] { from[0], from[1] - 1.6F, from[2] }, new float[] { from[0], drop, from[2] }, new float[] { to[0], drop, to[3] },
                        new float[] { to[0], to[1] - 1.6F, to[3] });
            }
        }
        // The shield wire peak: four members from the body's top corners to a post.
        // Each runs into a gusset block under the post, ending clear of the others.
        plateBox(c - 2.4F, 492.0F, c - 2.4F, c + 2.4F, 496.0F, c + 2.4F, Mat.GALV);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                member(legAt(sx, sz, 464), new float[] { c + sx * 1.6F, 493.0F, c + sz * 1.6F }, 2.0F, 2.0F, new float[] { sx, 0, sz }, Mat.GALV, true);
            }
        }
        shieldPeak(c, 496, c, 14);
        // The frame (shapes and outline): the footings, then the body one box per bay, stepping in with the legs' batter
        // and wide enough for the anti-climb guard, the step bolts and the sign, then the peak's members to its post (the
        // arms add their own, in arm()).
        frame.add(new float[] { c - 44, 0, c - 44, c + 44, 8, c + 44 });
        for (int k = 0; k + 1 < levels.length; k++) {
            float h = legHalf(levels[k]) + 4.5F;
            frame.add(new float[] { c - h, levels[k], c - h, c + h, levels[k + 1], c + h });
        }
        frame.add(new float[] { c - 13.5F, 464, c - 13.5F, c + 13.5F, 496, c + 13.5F });
        antiClimb(c);
        stepBolts();
        // The danger sign on the south face's lowest X, at its crossing.
        float y = 32, z = c + legHalf(y) + 2.6F;
        swatchBox(c - 0.8F, y - 0.8F, z - 2.6F, c + 0.8F, y + 0.8F, z, GALV0 + 7);
        sign(c - 5, y - 6, z);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Lattice helpers
    // ---------------------------------------------------------------------------------------------------------------

    // The legs' half-spread at y: 36 px at the footings, battered in to 12 at the waist (y 304), straight above.
    private static float legHalf(float y) {
        return y >= 304 ? 12.0F : 36.0F - 24.0F * (y - 8.0F) / 296.0F;
    }

    private static float[] legAt(int sx, int sz, float y) {
        float h = legHalf(y);
        return new float[] { 8 + sx * h, y, 8 + sz * h };
    }

    // A face's two corners at y, left then right seen from outside (faces: 0 south +z, 1 east +x, 2 north -z, 3 west).
    private static float[][] faceEnds(int face, float y) {
        return switch (face) {
            case 0 -> new float[][] { legAt(-1, 1, y), legAt(1, 1, y) };
            case 1 -> new float[][] { legAt(1, 1, y), legAt(1, -1, y) };
            case 2 -> new float[][] { legAt(1, -1, y), legAt(-1, -1, y) };
            default -> new float[][] { legAt(-1, -1, y), legAt(-1, 1, y) };
        };
    }

    private static float[] faceNormal(int face) {
        return switch (face) {
            case 0 -> new float[] { 0, 0, 1 };
            case 1 -> new float[] { 1, 0, 0 };
            case 2 -> new float[] { 0, 0, -1 };
            default -> new float[] { -1, 0, 0 };
        };
    }

    // A crossarm out of the body's side (side -1 west, 1 east) at y, reaching `reach` px from the tower's middle: two top
    // chords level, two bottom chords from the body 24 px down rising to the tip, laced on its sides, top and bottom,
    // and an end frame. Returns the tip's x.
    private float arm(int side, float y, float reach) {
        float c = 8, root = c + side * 12.0F, tip = c + side * reach;
        float rootHalf = 12.0F, tipHalf = 3.0F, rootDepth = 24.0F, tipDepth = 4.0F;
        int panels = Math.max(2, Math.round((reach - 12.0F) / 14.0F));
        float[][] topN = new float[panels + 1][], topS = new float[panels + 1][], botN = new float[panels + 1][], botS = new float[panels + 1][];
        for (int i = 0; i <= panels; i++) {
            float f = i / (float) panels;
            float x = root + (tip - root) * f, half = rootHalf + (tipHalf - rootHalf) * f, depth = rootDepth + (tipDepth - rootDepth) * f;
            topN[i] = new float[] { x, y, c - half };
            topS[i] = new float[] { x, y, c + half };
            botN[i] = new float[] { x, y - depth, c - half };
            botS[i] = new float[] { x, y - depth, c + half };
        }
        float[] out = { side, 0, 0 };
        // Chords 1.8 px (a hair under the body's girts they start from), lacing 1.4.
        for (int i = 0; i < panels; i++) {
            member(topN[i], topN[i + 1], 1.8F, 1.8F, UP, Mat.GALV, false);
            member(topS[i], topS[i + 1], 1.8F, 1.8F, UP, Mat.GALV, false);
            member(botN[i], botN[i + 1], 1.8F, 1.8F, UP, Mat.GALV, false);
            member(botS[i], botS[i + 1], 1.8F, 1.8F, UP, Mat.GALV, false);
            // Side lacing: a zigzag on each side face, and on the top and bottom, each bar ending inside the chords so
            // the zigzag's bars don't overlap where they meet.
            boolean even = i % 2 == 0;
            brace(even ? botN[i] : topN[i], even ? topN[i + 1] : botN[i + 1], 0.9F, 1.4F, 1.4F, new float[] { 0, 0, -1 }, true);
            brace(even ? botS[i] : topS[i], even ? topS[i + 1] : botS[i + 1], 0.9F, 1.4F, 1.4F, new float[] { 0, 0, 1 }, true);
            brace(even ? topN[i] : topS[i], even ? topS[i + 1] : topN[i + 1], 0.9F, 1.4F, 1.4F, UP, true);
            brace(even ? botS[i] : botN[i], even ? botN[i + 1] : botS[i + 1], 0.9F, 1.4F, 1.4F, UP, true);
            if (i > 0) {
                brace(topN[i], botN[i], 0.9F, 1.4F, 1.4F, new float[] { 0, 0, -1 }, true);
                brace(topS[i], botS[i], 0.9F, 1.4F, 1.4F, new float[] { 0, 0, 1 }, true);
            }
        }
        // The end frame (its uprights between the cross bars) the string hangs from.
        member(topN[panels], topS[panels], 2.0F, 2.0F, out, Mat.GALV, true);
        member(botN[panels], botS[panels], 2.0F, 2.0F, out, Mat.GALV, true);
        brace(topN[panels], botN[panels], 1.0F, 1.6F, 1.6F, out, true);
        brace(topS[panels], botS[panels], 1.0F, 1.6F, 1.6F, out, true);
        // The arm's frame: its full depth and width from the body's side to just short of the string hung at its tip,
        // then the tip itself only as deep as it is there, so the string's own box stays clear to aim at.
        float neck = tip - side * 4.2F, tipEnd = tip + side * 1.0F;
        frame.add(new float[] { Math.min(root, neck), y - rootDepth - 0.9F, c - rootHalf - 0.9F, Math.max(root, neck), y + 0.9F, c + rootHalf + 0.9F });
        frame.add(new float[] { Math.min(neck, tipEnd), y - tipDepth - 0.9F, c - tipHalf - 0.9F, Math.max(neck, tipEnd), y + 0.9F, c + tipHalf + 0.9F });
        return tip - side * 1.0F;
    }

    // The anti-climb guard: three barbed bars round the body just outside the legs, 3.5 to 4.5 blocks up, on brackets.
    private void antiClimb(float c) {
        for (float y : new float[] { 60, 64, 68 }) {
            float h = legHalf(y) + 3.0F;
            float[][] corners = { { c - h, y, c - h }, { c + h, y, c - h }, { c + h, y, c + h }, { c - h, y, c + h } };
            for (int i = 0; i < 4; i++) {
                float[] a = corners[i], b = corners[(i + 1) % 4];
                // Each bar owns the corner it runs into: started 0.4 px past its first corner, run 0.4 px past its
                // second, so the four meet without overlapping.
                float[] along = normalize(sub(b, a));
                member(add(a, scale(along, 0.4F)), add(b, scale(along, 0.4F)), 0.8F, 0.8F, UP, Mat.GALV, true);
                // Barbs every 4 px: short spikes up and out, alternately.
                float len = length(sub(b, a));
                float[] dir = normalize(sub(b, a));
                for (float s = 2; s < len - 1; s += 4) {
                    float[] p = add(a, scale(dir, s));
                    boolean upward = ((int) (s / 4) + (int) y) % 2 == 0;
                    swatchBox(p[0] - 0.25F, p[1] + (upward ? 0.4F : -1.4F), p[2] - 0.25F, p[0] + 0.25F, p[1] + (upward ? 1.4F : -0.4F), p[2] + 0.25F,
                            GALV0 + 8);
                }
            }
            // Brackets from each leg out to the bars.
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    member(legAt(sx, sz, y), new float[] { c + sx * (h - 0.4F), y, c + sz * (h - 0.4F) }, 0.6F, 0.6F, UP, Mat.GALV, false);
                }
            }
        }
    }

    // Step bolts up the north-west leg, every 6 px from above the guard to the top, alternately west and north.
    private void stepBolts() {
        for (float y = 78; y < 460; y += 6) {
            float[] leg = legAt(-1, -1, y);
            boolean west = ((int) (y / 6)) % 2 == 0;
            float[] d = west ? new float[] { -1, 0, 0 } : new float[] { 0, 0, -1 };
            float[] p = add(leg, scale(d, 1.5F));
            float[] q = add(leg, scale(d, 4.2F));
            swatchBox(Math.min(p[0], q[0]) - (west ? 0 : 0.4F), y - 0.4F, Math.min(p[2], q[2]) - (west ? 0.4F : 0), Math.max(p[0], q[0]) + (west ? 0 : 0.4F),
                    y + 0.4F, Math.max(p[2], q[2]) + (west ? 0.4F : 0), GALV0 + 8);
        }
    }

    // ================================================================================================================
    // The transformers
    // ================================================================================================================

    // A substation transformer's size, by tier (px): the tank's half-width (x) and half-depth (z) and its height, its
    // primary (higher) tier (1 MV, 2 HV, 3 EHV; the secondary is one down), and its bushings' spacing along the edge.
    public record Substation(float halfX, float halfZ, float height, int high, float spacing) {
        public static final float PAD = 4, LID = 2;

        public static Substation of(Design design) {
            return switch (design) {
                case SUBSTATION_MV -> new Substation(13, 10, 26, 1, 9);
                case SUBSTATION_HV -> new Substation(17, 13, 34, 2, 11);
                default -> new Substation(21, 16, 42, 3, 13);
            };
        }

        // The lid's top, where the bushings stand.
        public float top() {
            return PAD + height + LID;
        }

        // The primary bushings stand along the north edge, the secondary along the south (z).
        public float primaryZ() {
            return 8 - halfZ + 4;
        }

        public float secondaryZ() {
            return 8 + halfZ - 4;
        }
    }

    // A Substation Transformer (MV: LV to MV, HV: MV to HV, EHV: HV to EHV): a concrete pad, the galvanized tank and its
    // lid, a radiator bank on each side (fins on headers), the conservator drum on its saddles, and two rows of porcelain
    // post bushings on the lid: the primary (higher tier, taller) along the north edge, the secondary along the south.
    // Wires leave each row its own way along the line.
    private void substation(Substation t) {
        float c = 8, y0 = Substation.PAD, y1 = y0 + t.height(), top = t.top();
        float padX = t.halfX() + 9, padZ = t.halfZ() + 5;
        concrete(c - padX, 0, c - padZ, c + padX, y0, c + padZ);
        plateBox(c - t.halfX(), y0, c - t.halfZ(), c + t.halfX(), y1, c + t.halfZ(), Mat.GALV);
        plateBox(c - t.halfX() - 1, y1, c - t.halfZ() - 1, c + t.halfX() + 1, top, c + t.halfZ() + 1, Mat.GALV);
        frame.add(new float[] { c - padX, 0, c - padZ, c + padX, y0, c + padZ });
        frame.add(new float[] { c - t.halfX() - 1, y0, c - t.halfZ() - 1, c + t.halfX() + 1, top, c + t.halfZ() + 1 });
        // Radiators: thin fins 3 px apart standing off each side, on a header top and bottom.
        float out = 7, f0 = y0 + 3, f1 = y1 - 3;
        for (int side = -1; side <= 1; side += 2) {
            float inner = c + side * t.halfX(), outer = inner + side * out;
            for (float z = c - t.halfZ() + 2.5F; z <= c + t.halfZ() - 2.5F; z += 3) {
                plateBox(Math.min(inner, outer), f0, z - 0.5F, Math.max(inner, outer), f1, z + 0.5F, Mat.GALV);
            }
            for (float y : new float[] { f0 - 1.5F, f1 + 0.5F }) {
                plateBox(Math.min(inner, outer) + (side > 0 ? 0 : 1), y, c - t.halfZ() + 1.5F, Math.max(inner, outer) - (side > 0 ? 1 : 0), y + 1,
                        c + t.halfZ() - 1.5F, Mat.GALV);
            }
            frame.add(new float[] { Math.min(inner, outer), f0 - 1.5F, c - t.halfZ() + 1.5F, Math.max(inner, outer), f1 + 1.5F, c + t.halfZ() - 1.5F });
        }
        // The conservator: a drum along x over the middle of the lid, on two saddles.
        float r = Math.max(3, t.halfZ() * 0.3F), cy = top + 3 + r, half = t.halfX() * 0.75F;
        member(new float[] { c - half, cy, c }, new float[] { c + half, cy, c }, 2 * r, 2 * r, UP, Mat.GALV, true);
        for (float x : new float[] { c - half * 0.6F, c + half * 0.6F }) {
            plateBox(x - 1, top, c - 1, x + 1, cy - r + 0.5F, c + 1, Mat.GALV);
        }
        frame.add(new float[] { c - half, top, c - r, c + half, cy + r, c + r });
        for (int i = -1; i <= 1; i++) {
            bushing(t.high(), c + i * t.spacing(), top, t.primaryZ(), false);
            bushing(t.high() - 1, c + i * t.spacing(), top, t.secondaryZ(), false);
        }
    }

    // A unit transformer's size by tier (0 LV to 3 EHV, px): it grows with the voltage. Its cable box fills the front
    // (north) 2.5 px of its block, the tank behind it (never into the block it serves), its bushings across the lid.
    public record Unit(int tier, float halfX, float halfZ, float height, float spacing) {
        public static final float BOX = 2.5F;

        public static Unit of(Design design) {
            int tier = design.ordinal() - Design.UNIT_LV.ordinal();
            float[] spacing = { 5.6F, 6.0F, 6.5F, 7.5F };
            return new Unit(tier, 6 + 2 * tier, 4.5F + 1.5F * tier, 9 + 3 * tier, spacing[tier]);
        }

        public float centreZ() {
            return BOX + halfZ;
        }

        // The lid's top, where the bushings stand.
        public float top() {
            return height + 1;
        }
    }

    // A Unit Transformer: a galvanized tank with fins on its sides, the cable box on its front (north, toward the FE block
    // it serves) and three bushings on the lid, A B C west to east, in its tier's own insulator (the same as its towers'
    // and the substations'). It grows from LV (about a block) to EHV (three across, three tall).
    private void unit(Unit u) {
        float c = 8, z0 = Unit.BOX, z1 = Unit.BOX + 2 * u.halfZ(), y1 = u.height();
        plateBox(c - u.halfX(), 0, z0, c + u.halfX(), y1, z1, Mat.GALV);
        plateBox(c - u.halfX() - 0.5F, y1, z0 - 0.5F, c + u.halfX() + 0.5F, u.top(), z1 + 0.5F, Mat.GALV);
        for (int side = -1; side <= 1; side += 2) {
            float inner = c + side * u.halfX(), outer = inner + side * 2;
            for (float z = z0 + 1.5F; z <= z1 - 1.5F; z += 2.5F) {
                plateBox(Math.min(inner, outer), 1.5F, z - 0.4F, Math.max(inner, outer), y1 - 1.5F, z + 0.4F, Mat.GALV);
            }
        }
        // The cable box, out to the block's north face.
        plateBox(c - 3, 1.5F, 0, c + 3, 6.5F, z0, Mat.GALV);
        frame.add(new float[] { c - u.halfX() - 2, 0, 0, c + u.halfX() + 2, u.top(), z1 + 0.5F });
        for (int i = -1; i <= 1; i++) {
            bushing(u.tier(), c + i * u.spacing(), u.top(), u.centreZ(), true);
        }
    }

    // A transformer's bushing at (x, y, z) in its tier's insulator (0 LV: the porcelain pin, 1 MV: the porcelain post,
    // 2 HV: four glass discs, 3 EHV: six larger ones; a unit transformer's a little smaller). Returns its top, where the
    // wire attaches (tools/tower_attach_groups_gen.py works the same).
    private float bushing(int tier, float x, float y, float z, boolean unit) {
        return switch (tier) {
            case 0 -> {
                pinInsulator(x, y, z);
                yield y + 7.6F;
            }
            case 1 -> {
                float height = unit ? 10 : 14;
                postInsulator(x, y, z, height);
                yield y + height;
            }
            case 2 -> glassStack(x, y, z, unit ? 3 : 4, unit ? 2.4F : 3.0F);
            default -> glassStack(x, y, z, unit ? 4 : 6, unit ? 3.0F : 4.0F);
        };
    }

    // An upright stack of cap-and-pin glass discs on a clevis (an HV or EHV bushing): each a steel pin, the glass rim and
    // glazed top stepping in, and the steel cap; a line clamp on top. Returns the clamp's top.
    private float glassStack(float x, float y, float z, int discs, float rim) {
        turned(x, z, new float[][] { { y, 1.0F }, { y + 1.0F, 1.0F } }, 8, Ramp.GALV, true, false);
        float s = y + 1.0F;
        for (int i = 0; i < discs; i++) {
            turned(x, z, new float[][] { { s, 0.6F }, { s + 0.6F, 0.6F } }, 6, Ramp.GALV, false, false);
            turned(x, z, new float[][] { { s + 0.6F, rim }, { s + 1.6F, rim }, { s + 1.6F, rim * 0.6F }, { s + 2.4F, rim * 0.6F } }, 8, Ramp.GLASS, true,
                    true);
            turned(x, z, new float[][] { { s + 2.4F, 1.2F }, { s + 3.0F, 1.2F } }, 8, Ramp.GALV, true, false);
            s += 3.0F;
        }
        turned(x, z, new float[][] { { s, 1.6F }, { s + 1.0F, 1.6F } }, 8, Ramp.GALV, true, true);
        insulators.add(new float[] { x - rim, y, z - rim, x + rim, s + 1.0F, z + rim });
        return s + 1.0F;
    }

    // ================================================================================================================
    // Protection, line hardware and the service drop
    // ================================================================================================================

    // A Fuse Cutout (LV or MV), one conductor's fuse, hung on the pole north of it: a steel bracket from inside the pole
    // (its middle a block north) to a porcelain body with sheds close by, a contact arm top (line) and bottom (load), and
    // the fuse tube between them; blown, the tube has dropped open on its bottom hinge. Its terminals are the contacts'
    // outer faces; its two insulators the body's upper (line) and lower (load) halves, contacts and all.
    public static final float FUSE_TOP = 15.2F, FUSE_BOTTOM = 4.8F, FUSE_Z = 3.5F;

    private void fuse(int tier, boolean blown) {
        float c = 8, z = FUSE_Z, y0 = 6, y1 = 14, r = tier == 0 ? 1.3F : 1.6F, shed = tier == 0 ? 2.2F : 2.7F;
        // To z -6.5: past the face of the thickest pole it hangs on (MV, 3.9 px round, centred at z -8).
        plateBox(c - 1.5F, 9, -6.5F, c + 1.5F, 11, z - r, Mat.GALV);
        List<float[]> profile = new ArrayList<>();
        profile.add(new float[] { y0, r });
        for (float y = y0 + 1; y < y1 - 1; y += 2) {
            profile.add(new float[] { y, r });
            profile.add(new float[] { y, shed });
            profile.add(new float[] { y + 0.6F, shed });
            profile.add(new float[] { y + 1.2F, r });
        }
        profile.add(new float[] { y1, r });
        turned(c, z, profile.toArray(new float[0][]), 8, Ramp.PORCELAIN, false, false);
        swatchBox(c - 1.2F, y1, z - 1.2F, c + 4.2F, FUSE_TOP, z + 1.2F, GALV0 + 7);
        swatchBox(c - 1.2F, FUSE_BOTTOM, z - 1.2F, c + 4.2F, y0, z + 1.2F, GALV0 + 7);
        float tx = c + 3.4F;
        if (blown) {
            member(new float[] { tx, y0, z }, new float[] { tx + 3.0F, 0.6F, z }, 1.6F, 1.6F, new float[] { 0, 0, 1 }, Mat.WOOD, true);
        } else {
            member(new float[] { tx, y0, z }, new float[] { tx, y1, z }, 1.6F, 1.6F, new float[] { 0, 0, 1 }, Mat.WOOD, true);
        }
        // The bracket's shape stops at the block's edge (the pole's block is the pole's).
        frame.add(new float[] { c - 1.5F, 9, 0, c + 1.5F, 11, z - r });
        float mid = (y0 + y1) / 2;
        insulators.add(new float[] { c - shed, mid, z - shed, c + 4.4F, FUSE_TOP, z + shed });
        insulators.add(new float[] { c - shed, FUSE_BOTTOM, z - shed, c + 4.4F, mid, z + shed });
    }

    // A recloser (LV, MV) or circuit breaker (HV, EHV): a galvanized tank on feet, its control cabinet on the east side
    // with the position flag (red closed, green open), and three bushings each side in its tier's insulator, the line
    // side north, the load side south. Sized by tier.
    public record Breaker(int tier, float halfX, float halfZ, float height, float spacing) {
        public static final float FEET = 2;

        public static Breaker of(int tier) {
            return new Breaker(tier, 6 + 3 * tier, 6 + 2 * tier, 9 + 4 * tier, new float[] { 5.6F, 6.0F, 6.5F, 8.0F }[tier]);
        }

        public float top() {
            return FEET + height + 1;
        }
    }

    private void breaker(int tier, boolean open) {
        Breaker b = Breaker.of(tier);
        float c = 8, y0 = Breaker.FEET, y1 = y0 + b.height();
        for (float x : new float[] { c - b.halfX() + 1, c + b.halfX() - 3 }) {
            plateBox(x, 0, c - b.halfZ() + 1, x + 2, y0, c + b.halfZ() - 1, Mat.GALV);
        }
        plateBox(c - b.halfX(), y0, c - b.halfZ(), c + b.halfX(), y1, c + b.halfZ(), Mat.GALV);
        plateBox(c - b.halfX() - 0.5F, y1, c - b.halfZ() - 0.5F, c + b.halfX() + 0.5F, b.top(), c + b.halfZ() + 0.5F, Mat.GALV);
        float cab = 3 + tier;
        plateBox(c + b.halfX(), y0 + 1, c - b.halfZ() * 0.6F, c + b.halfX() + cab, y0 + b.height() * 0.75F, c + b.halfZ() * 0.6F, Mat.GALV);
        float fy = y0 + b.height() * 0.45F;
        swatchBox(c + b.halfX() + cab, fy, c - 1.2F, c + b.halfX() + cab + 0.6F, fy + 2.4F, c + 1.2F, open ? MARKER : RED);
        frame.add(new float[] { c - b.halfX() - 0.5F, 0, c - b.halfZ() - 0.5F, c + b.halfX() + cab + 0.6F, b.top(), c + b.halfZ() + 0.5F });
        for (int i = -1; i <= 1; i++) {
            bushing(tier, c + i * b.spacing(), b.top(), c - b.halfZ() + 2.5F, true);
            bushing(tier, c + i * b.spacing(), b.top(), c + b.halfZ() - 2.5F, true);
        }
    }

    // A Disconnect Switch: a galvanized base and, per phase, two post insulators of its tier (line side north, load side
    // south) bridged by a steel blade: lying across the posts' tops when closed, swung up off the line-side post when
    // open (a visible break, as on a real air-break switch).
    public static final float[] DISCONNECT_SPACING = { 6, 7, 9, 12 }, DISCONNECT_HALF = { 5, 6, 8, 10 };
    public static final float DISCONNECT_BASE = 2;

    private void disconnect(int tier, boolean open) {
        float c = 8, sp = DISCONNECT_SPACING[tier], half = DISCONNECT_HALF[tier], base = DISCONNECT_BASE;
        plateBox(c - sp - 3.5F, 0, c - half - 2.5F, c + sp + 3.5F, base, c + half + 2.5F, Mat.GALV);
        frame.add(new float[] { c - sp - 3.5F, 0, c - half - 2.5F, c + sp + 3.5F, base, c + half + 2.5F });
        for (int i = -1; i <= 1; i++) {
            float x = c + i * sp;
            float top = bushing(tier, x, base, c - half, true);
            bushing(tier, x, base, c + half, true);
            float[] hinge = { x, top + 0.6F, c - half };
            float[] end = open ? new float[] { x, top + 0.6F + 1.7F * half, c - half + 0.9F * half } : new float[] { x, top + 0.6F, c + half };
            member(hinge, end, 1.2F, 0.7F, new float[] { 1, 0, 0 }, Mat.GALV, true);
        }
    }

    // A strain (dead-end) insulator from a crossarm's face at z0 out along dir (-1 north, +1 south), at (x, y), lying
    // along the line: a galvanized clevis rod, the same glazed porcelain as the pole's own insulators (a core with a shed
    // every 2 px: the LV pin's size on LV, the MV post's on MV), and the round line clamp, its outer face (the terminal)
    // at z0 + dir * STRAIN.
    public static final float STRAIN = 8.2F;

    private float strain(float x, float y, float z0, float dir, boolean mv) {
        float core = mv ? 1.3F : 1.0F, shed = mv ? 2.6F : 2.2F;
        int solidFrom = solid.size(), boxesFrom = boxes.size();
        turned(0, 0, new float[][] { { 0, 0.6F }, { 1.0F, 0.6F } }, 6, Ramp.GALV, false, false);
        List<float[]> profile = new ArrayList<>();
        profile.add(new float[] { 1.0F, core });
        for (float s = 1.5F; s < 6.0F; s += 2.0F) {
            profile.add(new float[] { s, core });
            profile.add(new float[] { s, shed });
            profile.add(new float[] { s + 0.6F, shed });
            profile.add(new float[] { s + 1.2F, core });
        }
        profile.add(new float[] { 7.0F, core });
        turned(0, 0, profile.toArray(new float[0][]), 8, Ramp.PORCELAIN, true, true);
        turned(0, 0, new float[][] { { 7.0F, 1.2F }, { STRAIN, 1.2F } }, 8, Ramp.GALV, true, true);
        alongZ(solidFrom, x, y, z0, dir);
        boxes.subList(boxesFrom, boxes.size()).clear();
        float c1 = z0 + dir * STRAIN;
        float[] box = { x - shed, y - shed, Math.min(z0, c1), x + shed, y + shed, Math.max(z0, c1) };
        boxes.add(box);
        insulators.add(box);
        return c1;
    }

    // Lays the quads built since `from` (round about the upright axis at the origin, from y 0 up) along z from z0 the way
    // dir points, centred on (x, y): a quarter turn about x (a turn, not a mirror, so faces still look out).
    private void alongZ(int from, float x, float y, float z0, float dir) {
        for (int i = from; i < solid.size(); i += 8) {
            float px = solid.get(i), py = solid.get(i + 1), pz = solid.get(i + 2);
            solid.set(i, x / PX + px);
            solid.set(i + 1, y / PX - dir * pz);
            solid.set(i + 2, z0 / PX + dir * py);
            float nx = solid.get(i + 5), ny = solid.get(i + 6), nz = solid.get(i + 7);
            solid.set(i + 5, nx);
            solid.set(i + 6, -dir * nz);
            solid.set(i + 7, dir * ny);
        }
    }

    // The jumper loop under a dead-end's crossarm, from one strain's clamp to the other's: down, across and up, in
    // copper.
    private void jumperLoop(float x, float y, float zNorth, float zSouth, float drop) {
        swatchBox(x - 0.3F, y - drop, zNorth - 0.3F, x + 0.3F, y - 1.0F, zNorth + 0.3F, COPPER0 + 1);
        swatchBox(x - 0.3F, y - drop - 0.6F, zNorth - 0.3F, x + 0.3F, y - drop, zSouth + 0.3F, COPPER0 + 1);
        swatchBox(x - 0.3F, y - drop, zSouth - 0.3F, x + 0.3F, y - 1.0F, zSouth + 0.3F, COPPER0 + 1);
    }

    // LV Dead-End Pole: the LV pole with a crossarm on both faces and a strain string out each way at each phase (A B C
    // where the LV pole's pins are, so lines line up) with its jumper loop, and the neutral's standoff strained both ways:
    // where a line ends or turns. Its north and south terminals of each conductor are joined through it.
    public static final float DEAD_END_LV_ARM = 132, DEAD_END_LV_NORTH = 3, DEAD_END_LV_SOUTH = 13;

    private void deadEndLv() {
        float cx = 8, cz = 8, top = 150, armY = DEAD_END_LV_ARM;
        pole(cx, cz, 0, top, 8, 2.0F, false, 140);
        timber(-12, armY, DEAD_END_LV_NORTH, 28, armY + 4, DEAD_END_LV_NORTH + 3);
        timber(-12, armY, DEAD_END_LV_SOUTH - 3, 28, armY + 4, DEAD_END_LV_SOUTH);
        swatchBox(7.5F, armY + 1.5F, DEAD_END_LV_NORTH - 0.6F, 8.5F, armY + 2.5F, DEAD_END_LV_SOUTH + 0.6F, GALV0 + 7);
        for (float x : new float[] { -8, 1, 24 }) {
            float n = strain(x, armY + 2, DEAD_END_LV_NORTH, -1, false), so = strain(x, armY + 2, DEAD_END_LV_SOUTH, 1, false);
            jumperLoop(x, armY + 2, n + 0.5F, so - 0.5F, 7);
        }
        float ny = NEUTRAL_Y, nx = NEUTRAL_X;
        swatchBox(nx - 1.2F, ny - 1.4F, cz - 0.7F, cx - 1.0F, ny, cz + 0.7F, GALV0 + 6);
        float n = strain(nx, ny - 0.7F, cz - 0.7F, -1, false), so = strain(nx, ny - 0.7F, cz + 0.7F, 1, false);
        jumperLoop(nx, ny - 0.7F, n + 0.5F, so - 0.5F, 5);
        tag(cx, 40, cz + 2.41F, true);
    }

    // MV Dead-End Pole: the MV pole with both crossarms doubled (north and south faces) and a strain string out each way at
    // each phase (A on the upper arm, B and C on the lower, where the MV pole's posts are) with its jumper loop.
    public static final float DEAD_END_MV_UPPER = 196, DEAD_END_MV_LOWER = 166;

    public static float deadEndMvNorth() {
        return 8 - 3.62F - 3.6F + 0.4F;
    }

    public static float deadEndMvSouth() {
        return 8 + 3.62F + 3.6F - 0.4F + 0.4F;
    }

    private void deadEndMv() {
        float cx = 8, cz = 8, top = 222, face = cz + 3.62F, back = cz - 3.62F;
        pole(cx, cz, 0, top, 8, 3.0F, true, 260);
        float north = deadEndMvNorth(), south = deadEndMvSouth();
        for (float y : new float[] { DEAD_END_MV_UPPER, DEAD_END_MV_LOWER }) {
            float x0 = y == DEAD_END_MV_UPPER ? -12 : -20, x1 = y == DEAD_END_MV_UPPER ? 14 : 36;
            timber(x0, y, north, x1, y + 4, back + 0.4F);
            timber(x0, y, face - 0.4F, x1, y + 4, south);
            swatchBox(7.4F, y + 1.4F, north - 0.6F, 8.6F, y + 2.6F, south + 0.6F, GALV0 + 7);
        }
        float[][] phases = { { -8, DEAD_END_MV_UPPER }, { -16, DEAD_END_MV_LOWER }, { 32, DEAD_END_MV_LOWER } };
        for (float[] ph : phases) {
            float n = strain(ph[0], ph[1] + 2, north, -1, true), so = strain(ph[0], ph[1] + 2, south, 1, true);
            jumperLoop(ph[0], ph[1] + 2, n + 0.5F, so - 0.5F, 8);
        }
        shieldPeak(cx, top, cz, 16);
    }

    // The Service Meter: a steel mast with its weatherhead, a rack of three porcelain spools (L1, N, L2) where the
    // service drop ties on, and the meter box with its glass dome on the front (north, toward the block it feeds).
    public static final float SERVICE_SPOOL_TOP = 25.2F, SERVICE_SPOOL_Z = 9;
    public static final float[] SERVICE_SPOOL_X = { 4.5F, 8, 11.5F };

    private void service() {
        float c = 8, mz = 11;
        member(new float[] { c, 0, mz }, new float[] { c, 28, mz }, 2, 2, new float[] { 1, 0, 0 }, Mat.GALV, true);
        swatchBox(c - 1.8F, 28, mz - 1.8F, c + 1.8F, 29.5F, mz + 1.8F, GALV0 + 6);
        swatchBox(c - 5.5F, 21.8F, SERVICE_SPOOL_Z - 0.6F, c + 5.5F, 22.8F, mz - 1, GALV0 + 7);
        for (float x : SERVICE_SPOOL_X) {
            turned(x, SERVICE_SPOOL_Z, new float[][] { { 22.8F, 1.0F }, { 23.2F, 1.4F }, { 24.6F, 1.4F }, { SERVICE_SPOOL_TOP, 1.0F } }, 8,
                    Ramp.PORCELAIN, true, false);
            insulators.add(new float[] { x - 1.4F, 22.8F, SERVICE_SPOOL_Z - 1.4F, x + 1.4F, SERVICE_SPOOL_TOP, SERVICE_SPOOL_Z + 1.4F });
        }
        plateBox(c - 3, 6, c - 1.5F, c + 3, 13, mz - 1, Mat.GALV);
        swatchBox(c - 2, 7.5F, c - 2.2F, c + 2, 11.5F, c - 1.5F, GLASS0 + 4);
        frame.add(new float[] { c - 1.8F, 0, mz - 1.8F, c + 1.8F, 29.5F, mz + 1.8F });
        frame.add(new float[] { c - 3, 6, c - 2.2F, c + 3, 13, mz - 1 });
    }

    // ================================================================================================================
    // Parts
    // ================================================================================================================

    // A treated wood pole from y0 to y1 round (cx, cz): `sides` facets fw px round, each showing its column of the pole
    // skin (the whole skin row from vOffset, so the bands meet round the pole), and a cap of end grain on top.
    private void pole(float cx, float cz, float y0, float y1, int sides, float fw, boolean mvSkin, int vOffset) {
        float r = (float) (fw / (2.0 * Math.sin(Math.PI / sides)));
        float[][] rim = ring(cx, cz, r, sides);
        float length = y1 - y0;
        int v = Math.min(SKIN_H - (int) Math.ceil(length), vOffset);
        for (int i = 0; i < sides; i++) {
            float[] a = rim[i], b = rim[(i + 1) % sides];
            // Right runs the way the angle falls, so right x up looks out of the pole.
            float[] base = { b[0], y0, b[2] };
            float[] right = normalize(sub(a, b));
            int column = mvSkin ? SKIN_MV_X + 3 * (i % 10) : SKIN_LV_X + Math.floorMod((sides - 1 - i) * Math.round(fw), SKIN_LV_W);
            List<float[]> st = List.of(new float[] { 0, 0 }, new float[] { fw, 0 }, new float[] { fw, length }, new float[] { 0, length });
            paintPlane(st, base, right, UP, List.of(new float[] { 0, fw, 0, length, column, v }));
        }
        // The top: end grain, its rings centred.
        List<float[]> st = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            st.add(new float[] { rim[i][0] - (cx - r), (cz + r) - rim[i][2] });
        }
        float size = 2 * r;
        float off = (12 - size) / 2.0F;
        paintPlane(st, new float[] { cx - r, y1, cz + r }, new float[] { 1, 0, 0 }, new float[] { 0, 0, -1 },
                List.of(new float[] { 0, size, 0, size, GRAIN_X + off, GRAIN_Y + off }));
        // The butt, end grain too (out of sight in the ground, but closed if the tower stands on a slab or an edge).
        List<float[]> butt = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            butt.add(new float[] { rim[i][0] - (cx - r), rim[i][2] - (cz - r) });
        }
        paintPlane(butt, new float[] { cx - r, y0, cz - r }, new float[] { 1, 0, 0 }, new float[] { 0, 0, 1 },
                List.of(new float[] { 0, size, 0, size, GRAIN_X + off, GRAIN_Y + off }));
        boxes.add(new float[] { cx - r, y0, cz - r, cx + r, y1, cz + r });
        frame.add(new float[] { cx - r, y0, cz - r, cx + r, y1, cz + r });
    }

    private static float[][] ring(float cx, float cz, float r, int sides) {
        float[][] rim = new float[sides][];
        for (int i = 0; i < sides; i++) {
            double angle = Math.PI / sides + i * 2.0 * Math.PI / sides;
            rim[i] = new float[] { cx + (float) (Math.cos(angle) * r), 0, cz + (float) (Math.sin(angle) * r) };
        }
        return rim;
    }

    // A sawn treated timber (a crossarm or crossbeam), its grain along its length.
    private void timber(float x0, float y0, float z0, float x1, float y1, float z1) {
        float[] a = { x0, (y0 + y1) / 2, (z0 + z1) / 2 }, b = { x1, (y0 + y1) / 2, (z0 + z1) / 2 };
        member(a, b, z1 - z0, y1 - y0, UP, Mat.WOOD, true);
        frame.add(new float[] { x0, y0, z0, x1, y1, z1 });
    }

    // A concrete box (a footing step).
    private void concrete(float x0, float y0, float z0, float x1, float y1, float z1) {
        float[] a = { x0, (y0 + y1) / 2, (z0 + z1) / 2 }, b = { x1, (y0 + y1) / 2, (z0 + z1) / 2 };
        member(a, b, z1 - z0, y1 - y0, UP, Mat.CONCRETE, true);
    }

    // A porcelain pin insulator standing at (x, y, z): a steel pin, two skirts and a crown with the tie groove (where a
    // wire will be tied).
    private void pinInsulator(float x, float y, float z) {
        turned(x, z, new float[][] { { y, 0.6F }, { y + 2.0F, 0.6F } }, 6, Ramp.GALV, false, false);
        float b = y + 2.0F;
        turned(x, z, new float[][] { { b, 1.0F }, { b, 2.7F }, { b + 0.9F, 2.7F }, { b + 2.0F, 1.6F }, { b + 2.6F, 1.6F }, { b + 2.6F, 2.2F },
                { b + 3.4F, 2.2F }, { b + 4.0F, 1.5F }, { b + 4.2F, 1.2F }, { b + 4.8F, 1.2F }, { b + 4.9F, 1.6F }, { b + 5.6F, 1.5F } }, 8, Ramp.PORCELAIN,
                true, true);
        insulators.add(new float[] { x - 2.7F, y, z - 2.7F, x + 2.7F, b + 5.6F, z + 2.7F });
    }

    // A porcelain line post insulator standing at (x, y, z), `height` px: a steel base flange, the core with a shed every
    // 2 px, and a steel line clamp on top.
    private void postInsulator(float x, float y, float z, float height) {
        postInsulator(x, y, z, height, Ramp.PORCELAIN);
    }

    // The same in another glaze: HV and EHV posts are the towers' blue-green glass.
    private void postInsulator(float x, float y, float z, float height, Ramp glaze) {
        turned(x, z, new float[][] { { y, 2.2F }, { y + 1.0F, 2.2F } }, 8, Ramp.GALV, true, false);
        float top = y + height - 1.6F;
        List<float[]> profile = new ArrayList<>();
        profile.add(new float[] { y + 1.0F, 1.3F });
        for (float s = y + 2.0F; s < top - 1.0F; s += 2.0F) {
            profile.add(new float[] { s, 1.3F });
            profile.add(new float[] { s, 2.6F });
            profile.add(new float[] { s + 0.6F, 2.6F });
            profile.add(new float[] { s + 1.2F, 1.3F });
        }
        profile.add(new float[] { top, 1.3F });
        turned(x, z, profile.toArray(new float[0][]), 8, glaze, true, false);
        // The clamp: a saddle across the line (along u) on a short stud.
        turned(x, z, new float[][] { { top, 1.6F }, { top + 0.6F, 1.6F } }, 8, Ramp.GALV, true, true);
        swatchBox(x - 1.0F, top + 0.6F, z - 2.2F, x + 1.0F, top + 1.6F, z + 2.2F, GALV0 + 6);
        insulators.add(new float[] { x - 2.6F, y, z - 2.6F, x + 2.6F, top + 1.6F, z + 2.6F });
    }

    // A suspension string hanging from (x, y, z): a steel clevis and socket, `discs` glass discs at a 3 px pitch, and a
    // suspension clamp along the line at the bottom.
    private void suspension(float x, float y, float z, int discs) {
        swatchBox(x - 1.2F, y - 0.6F, z - 1.2F, x + 1.2F, y, z + 1.2F, GALV0 + 7);
        turned(x, z, new float[][] { { y - 2.2F, 0.5F }, { y - 0.6F, 0.5F } }, 6, Ramp.GALV, false, false);
        float t = y - 2.2F;
        for (int i = 0; i < discs; i++) {
            disc(x, z, t);
            t -= 3.0F;
        }
        // The clamp: a yoke down from the last disc's pin and a trough along the line.
        turned(x, z, new float[][] { { t - 0.8F, 0.6F }, { t, 0.6F } }, 6, Ramp.GALV, false, false);
        swatchBox(x - 1.0F, t - 2.2F, z - 2.6F, x + 1.0F, t - 0.8F, z + 2.6F, GALV0 + 6);
        swatchBox(x - 0.6F, t - 3.0F, z - 1.4F, x + 0.6F, t - 2.2F, z + 1.4F, GALV0 + 4);
        boxes.add(new float[] { x - 3.1F, t - 3.0F, z - 3.1F, x + 3.1F, y, z + 3.1F });
        insulators.add(new float[] { x - 3.1F, t - 3.0F, z - 3.1F, x + 3.1F, y, z + 3.1F });
    }

    // One cap-and-pin glass disc whose cap top is at y: the steel cap, the glazed top falling from cap to rim (its own
    // ramp from rim to cap, and the ramp round it), the rim, the underside and the pin down to the next cap.
    private void disc(float x, float z, float y) {
        turned(x, z, new float[][] { { y - 0.7F, 1.0F }, { y, 1.0F }, { y, 0.0F } }, 8, Ramp.GALV, false, false);
        coneTop(x, z, y - 1.9F, 3.0F, y - 0.7F, 1.0F, 8, DISC_GLASS_X);
        turned(x, z, new float[][] { { y - 2.5F, 3.0F }, { y - 1.9F, 3.0F } }, 8, Ramp.GLASS, false, false);
        // The underside, closed right in to the pin.
        turned(x, z, new float[][] { { y - 2.5F, 0.45F }, { y - 2.5F, 3.0F } }, 8, Ramp.GLASS, false, false);
        turned(x, z, new float[][] { { y - 3.0F, 0.45F }, { y - 2.5F, 0.45F } }, 6, Ramp.GALV, false, false);
    }

    // A shield wire peak on (x, y, z): a steel post `height` px with a gusset collar and the clamp on top.
    private void shieldPeak(float x, float y, float z, float height) {
        turned(x, z, new float[][] { { y, 2.6F }, { y + 1.0F, 2.6F } }, 8, Ramp.GALV, true, false);
        member(new float[] { x, y + 0.5F, z }, new float[] { x, y + height - 2.0F, z }, 2.0F, 2.0F, new float[] { 1, 0, 0 }, Mat.GALV, true);
        swatchBox(x - 1.2F, y + height - 2.0F, z - 2.0F, x + 1.2F, y + height - 0.6F, z + 2.0F, GALV0 + 6);
        swatchBox(x - 0.6F, y + height - 0.6F, z - 1.2F, x + 0.6F, y + height, z + 1.2F, GALV0 + 7);
        frame.add(new float[] { x - 2.6F, y, z - 2.6F, x + 2.6F, y + height, z + 2.6F });
    }

    // The pole-mounted transformer can from y0 to y1 at (x, z): 8 facets of 4 px with the ramp across each, a seam at
    // the lid's flange, the lid's rings on top. Returns its radius.
    private float can(float x, float z, float y0, float y1) {
        float r = (float) (4.0 / (2.0 * Math.sin(Math.PI / 8)));
        turned(x, z, new float[][] { { y0, r - 0.6F }, { y0 + 0.8F, r }, { y1 - 1.6F, r } }, 8, Ramp.CAN, false, false);
        lid(x, y0, z, r - 0.6F, false);
        turned(x, z, new float[][] { { y1 - 1.6F, r + 0.5F }, { y1 - 0.8F, r + 0.5F }, { y1 - 0.8F, r - 0.2F } }, 8, Ramp.CAN, false, true);
        lid(x, y1 - 0.8F, z, r - 0.2F, true);
        boxes.add(new float[] { x - r, y0, z - r, x + r, y1, z + r });
        return r;
    }

    // A primary bushing standing on the lid at (x, y, z): porcelain, two sheds, its top (the wire's attach point) at
    // y + 3.2.
    private void primaryBushing(float x, float y, float z) {
        turned(x, z, new float[][] { { y, 0.9F }, { y + 1.2F, 0.9F }, { y + 1.2F, 1.3F }, { y + 1.8F, 1.3F }, { y + 1.8F, 0.9F },
                { y + 2.6F, 0.9F }, { y + 2.6F, 1.2F }, { y + 3.2F, 1.2F } }, 6, Ramp.PORCELAIN, true, false);
    }

    // A round flat end in the lid's rings (8 facets of radius r at y), looking up or down.
    private void lid(float x, float y, float z, float r, boolean up) {
        float[][] rim = ring(x, z, r, 8);
        List<float[]> st = new ArrayList<>();
        float size = 2 * r, off = (12 - size) / 2.0F;
        for (float[] p : rim) {
            st.add(new float[] { p[0] - (x - r), up ? (z + r) - p[2] : p[2] - (z - r) });
        }
        float[] base = up ? new float[] { x - r, y, z + r } : new float[] { x - r, y, z - r };
        paintPlane(st, base, new float[] { 1, 0, 0 }, up ? new float[] { 0, 0, -1 } : new float[] { 0, 0, 1 },
                List.of(new float[] { 0, size, 0, size, LID_X + off, LID_Y + off }));
    }

    // A flat steel V brace: two straps from one bolt at (x, y0, z0) on a pole's face up and out `spread` px each way
    // (west and / or east) to an arm's underside at (y1, z1).
    private void vBrace(float x, float y0, float z0, float y1, float z1, float spread, float w, float d, boolean west, boolean east) {
        for (float side : new float[] { -1, 1 }) {
            if (side < 0 ? !west : !east) {
                continue;
            }
            member(new float[] { x + side * 0.6F, y0, z0 }, new float[] { x + side * spread, y1, z1 }, w, d, new float[] { 0, 0, 1 }, Mat.GALV, true);
        }
        swatchBox(x - 0.8F, y0 - 0.8F, z0 - 1.0F, x + 0.8F, y0 + 0.8F, z0 + d / 2 + 0.4F, GALV0 + 7);
    }

    // An axis-aligned box plated in m.
    private void plateBox(float x0, float y0, float z0, float x1, float y1, float z1, Mat m) {
        member(new float[] { x0, (y0 + y1) / 2, (z0 + z1) / 2 }, new float[] { x1, (y0 + y1) / 2, (z0 + z1) / 2 }, z1 - z0, y1 - y0, UP, m, true);
    }

    // The pole tag: a 3 x 4 px aluminium plate nailed on the face at z (facing +z when south), its stamped face the
    // plate's own outer face.
    private void tag(float x, float y, float z, boolean south) {
        float d = south ? 1 : -1, face = z + d * 0.3F;
        swatchBoxOpen(x - 1.5F, y, Math.min(z, face), x + 1.5F, y + 4, Math.max(z, face), ALU0 + 1, south ? 2 : 1);
        List<float[]> st = List.of(new float[] { 0, 0 }, new float[] { 3, 0 }, new float[] { 3, 4 }, new float[] { 0, 4 });
        float[] base = south ? new float[] { x - 1.5F, y, face } : new float[] { x + 1.5F, y, face };
        float[] right = south ? new float[] { 1, 0, 0 } : new float[] { -1, 0, 0 };
        paintPlane(st, base, right, UP, List.of(new float[] { 0, 3, 0, 4, TAG_X, TAG_Y }));
    }

    // The danger sign: a 10 x 12 px plate on the south side with its left bottom corner at (x, y), its back at z: its
    // back and edges plated steel, its face the sign itself.
    private void sign(float x, float y, float z) {
        float f = z + 0.5F;
        face(List.of(new float[] { x, y, z }, new float[] { x, y + 12, z }, new float[] { x + 10, y + 12, z }, new float[] { x + 10, y, z }),
                new float[] { 0, 0, -1 }, null, Mat.GALV);
        face(List.of(new float[] { x, y + 12, z }, new float[] { x, y + 12, f }, new float[] { x + 10, y + 12, f }, new float[] { x + 10, y + 12, z }), UP,
                new float[] { 1, 0, 0 }, Mat.GALV);
        face(List.of(new float[] { x, y, z }, new float[] { x + 10, y, z }, new float[] { x + 10, y, f }, new float[] { x, y, f }), new float[] { 0, -1, 0 },
                new float[] { 1, 0, 0 }, Mat.GALV);
        face(List.of(new float[] { x, y, z }, new float[] { x, y, f }, new float[] { x, y + 12, f }, new float[] { x, y + 12, z }), new float[] { -1, 0, 0 },
                UP, Mat.GALV);
        face(List.of(new float[] { x + 10, y, z }, new float[] { x + 10, y + 12, z }, new float[] { x + 10, y + 12, f }, new float[] { x + 10, y, f }),
                new float[] { 1, 0, 0 }, UP, Mat.GALV);
        List<float[]> st = List.of(new float[] { 0, 0 }, new float[] { 10, 0 }, new float[] { 10, 12 }, new float[] { 0, 12 });
        paintPlane(st, new float[] { x, y, f }, new float[] { 1, 0, 0 }, UP, List.of(new float[] { 0, 10, 0, 12, SIGN_X, SIGN_Y }));
    }

    // ================================================================================================================
    // The yard and the line's heavy hardware (Phase 4)
    // ================================================================================================================

    // A strain string of `discs` cap-and-pin glass discs lying along the line from z0 the way dir points (-1 north, +1
    // south), centred on (x, y): a clevis, the discs (each its pin, glass rim and cap) and the line clamp, its outer face
    // (the terminal) at z0 + dir * glassStrainLength(discs).
    public static float glassStrainLength(int discs) {
        return 1.0F + 3.0F * discs + 1.6F;
    }

    private float glassStrain(float x, float y, float z0, float dir, int discs, float rim) {
        int solidFrom = solid.size(), boxesFrom = boxes.size();
        turned(0, 0, new float[][] { { 0, 0.6F }, { 1.0F, 0.6F } }, 6, Ramp.GALV, false, false);
        float s = 1.0F;
        for (int i = 0; i < discs; i++) {
            turned(0, 0, new float[][] { { s, 0.6F }, { s + 0.6F, 0.6F } }, 6, Ramp.GALV, false, false);
            turned(0, 0, new float[][] { { s + 0.6F, rim }, { s + 1.6F, rim }, { s + 1.6F, rim * 0.6F }, { s + 2.4F, rim * 0.6F } }, 8, Ramp.GLASS, true,
                    true);
            turned(0, 0, new float[][] { { s + 2.4F, 1.2F }, { s + 3.0F, 1.2F } }, 8, Ramp.GALV, true, false);
            s += 3.0F;
        }
        turned(0, 0, new float[][] { { s, 1.4F }, { s + 1.6F, 1.4F } }, 8, Ramp.GALV, true, true);
        alongZ(solidFrom, x, y, z0, dir);
        boxes.subList(boxesFrom, boxes.size()).clear();
        float end = z0 + dir * glassStrainLength(discs);
        float[] box = { x - rim, y - rim, Math.min(z0, end), x + rim, y + rim, Math.max(z0, end) };
        boxes.add(box);
        insulators.add(box);
        return end;
    }

    // A round pipe (bus or conduit) of radius r from z0 to z1 along z, centred on (x, y).
    private void pipe(float x, float y, float z0, float z1, float r) {
        int solidFrom = solid.size(), boxesFrom = boxes.size();
        turned(0, 0, new float[][] { { 0, r }, { z1 - z0, r } }, 8, Ramp.GALV, true, true);
        alongZ(solidFrom, x, y, z0, 1);
        boxes.subList(boxesFrom, boxes.size()).clear();
        boxes.add(new float[] { x - r, y - r, z0, x + r, y + r, z1 });
    }

    // A thin run in one flat tone from point to point (a jumper, a cable), w px square, its sides only.
    private void rod(float[] a, float[] b, float w, int swatch) {
        float[] d = normalize(sub(b, a));
        float[] u = Math.abs(d[1]) < 0.9F ? UP : new float[] { 1, 0, 0 };
        float[] s = scale(normalize(cross(d, u)), w / 2), t = scale(normalize(cross(s, d)), w / 2);
        float[][] off = { add(s, t), sub(t, s), scale(add(s, t), -1), sub(s, t) };
        float[] uv = swatchUv(swatch);
        for (int i = 0; i < 4; i++) {
            float[] o0 = off[i], o1 = off[(i + 1) % 4];
            float[] p0 = add(a, o0), p1 = add(b, o0), p2 = add(b, o1), p3 = add(a, o1);
            float[] n = cross(sub(p2, p0), sub(p3, p1));
            if (dot(n, add(o0, o1)) < 0) {
                quad(p3, p2, p1, p0, uv);
            } else {
                quad(p0, p1, p2, p3, uv);
            }
        }
        boxes.add(new float[] { Math.min(a[0], b[0]) - w, Math.min(a[1], b[1]) - w, Math.min(a[2], b[2]) - w, Math.max(a[0], b[0]) + w,
                Math.max(a[1], b[1]) + w, Math.max(a[2], b[2]) + w });
    }

    // A copper jumper through the points in order.
    private void jumper(float[]... points) {
        for (int i = 0; i + 1 < points.length; i++) {
            rod(points[i], points[i + 1], 0.8F, COPPER0 + 1);
        }
    }

    // Bus (HV, EHV): a section of rigid pipe bus two blocks long along the line, one pipe a phase on a station post
    // insulator of its tier's porcelain on a galvanized pedestal, a tap clamp on each pipe's middle (its terminal).
    // Sections placed end to end in a row join their phases.
    public static final float[] BUS_SPACING = { 0, 0, 16, 24 }, BUS_STAND = { 0, 0, 24, 32 }, BUS_POST = { 0, 0, 24, 36 }, BUS_PIPE = { 0, 0, 1.5F, 2.0F };
    public static final float BUS_Z0 = -8, BUS_Z1 = 24;

    public static float busTap(int tier) {
        return BUS_STAND[tier] + 1 + BUS_POST[tier] + 2 * BUS_PIPE[tier] + 0.8F;
    }

    private void bus(int tier) {
        float c = 8, sp = BUS_SPACING[tier], stand = BUS_STAND[tier], post = BUS_POST[tier], r = BUS_PIPE[tier];
        for (int i = -1; i <= 1; i++) {
            float x = c + i * sp;
            concrete(x - 4, 0, c - 4, x + 4, 2, c + 4);
            member(new float[] { x, 2, c }, new float[] { x, stand, c }, 3, 3, new float[] { 1, 0, 0 }, Mat.GALV, true);
            plateBox(x - 2.5F, stand, c - 2.5F, x + 2.5F, stand + 1, c + 2.5F, Mat.GALV);
            postInsulator(x, stand + 1, c, post, Ramp.GLASS);
            float y = stand + 1 + post + r;
            pipe(x, y, BUS_Z0, BUS_Z1, r);
            float[] run = { x - r, y - r, BUS_Z0, x + r, y + r, BUS_Z1 };
            insulators.add(run);
            swatchBox(x - 1.0F, y + r - 0.4F, c - 1.2F, x + 1.0F, y + r + 0.8F, c + 1.2F, GALV0 + 6);
            frame.add(new float[] { x - 4, 0, c - 4, x + 4, stand + 1, c + 4 });
        }
    }

    // Dead-End Gantry (HV, EHV): where lines come into the yard. Two galvanized A-frame legs (each two members spread
    // along the line) carry a box girder; each phase dead-ends on its north face on a glass strain string, and a copper
    // jumper drops under the girder to a suspension string on its south side (the yard's terminal). Shield peaks on the
    // legs.
    public static final float[] GANTRY_SPACING = { 0, 0, 20, 28 }, GANTRY_BEAM = { 0, 0, 64, 96 }, GANTRY_LEG = { 0, 0, 32, 48 };
    public static final int[] GANTRY_DISCS = { 0, 0, 6, 8 }, GANTRY_DROP = { 0, 0, 4, 6 };
    public static final float[] GANTRY_RIM = { 0, 0, 3.0F, 4.0F };
    public static final float GANTRY_HALF = 2.5F;

    private void gantry(int tier) {
        float c = 8, sp = GANTRY_SPACING[tier], beamY = GANTRY_BEAM[tier], leg = GANTRY_LEG[tier];
        for (int side = -1; side <= 1; side += 2) {
            float x = c + side * leg;
            for (int dz = -1; dz <= 1; dz += 2) {
                concrete(x - 3, 0, c + dz * 16 - 3, x + 3, 2, c + dz * 16 + 3);
                member(new float[] { x, 2, c + dz * 16 }, new float[] { x, beamY, c + dz * 1.5F }, 3, 3, new float[] { 1, 0, 0 }, Mat.GALV, true);
            }
            member(new float[] { x, beamY * 0.45F, c - 8.2F }, new float[] { x, beamY * 0.45F, c + 8.2F }, 2, 2, UP, Mat.GALV, true);
            frame.add(new float[] { x - 2, 0, c - 19, x + 2, beamY, c + 19 });
            shieldPeak(x, beamY + 4, c, 14);
        }
        plateBox(c - leg - 4, beamY, c - GANTRY_HALF, c + leg + 4, beamY + 4, c + GANTRY_HALF, Mat.GALV);
        frame.add(new float[] { c - leg - 4, beamY, c - GANTRY_HALF, c + leg + 4, beamY + 4, c + GANTRY_HALF });
        float y = beamY + 2, discs = GANTRY_DISCS[tier];
        for (int i = -1; i <= 1; i++) {
            float x = c + i * sp;
            float end = glassStrain(x, y, c - GANTRY_HALF, -1, (int) discs, GANTRY_RIM[tier]);
            suspension(x, beamY, c + GANTRY_HALF + 3.2F, GANTRY_DROP[tier]);
            float bottom = beamY - 2.2F - 3.0F * GANTRY_DROP[tier] - 3.0F;
            jumper(new float[] { x, y - 1.4F, end }, new float[] { x, bottom - 6, end }, new float[] { x, bottom - 6, c + GANTRY_HALF + 3.2F },
                    new float[] { x, bottom - 1.0F, c + GANTRY_HALF + 3.2F });
        }
    }

    // Voltage Regulator (MV, HV): three single-phase step regulators on a galvanized platform: round tanks with their
    // source bushing north and load bushing south (its tier's insulator, a unit's size), a position indicator on each,
    // and the control cabinet on the east end.
    public static final float[] REG_SPACING = { 0, 11, 15, 0 }, REG_R = { 0, 4.5F, 5.5F, 0 }, REG_H = { 0, 14, 20, 0 };
    public static final float REG_DECK = 4;

    public static float regBushingZ(int tier, int dir) {
        return 8 + dir * (REG_R[tier] - 1.0F);
    }

    private void regulator(int tier) {
        float c = 8, sp = REG_SPACING[tier], r = REG_R[tier], h = REG_H[tier], deck = REG_DECK;
        float x0 = c - sp - r - 2, x1 = c + sp + r + 2;
        plateBox(x0, deck - 1.5F, c - r - 2, x1, deck, c + r + 2, Mat.GALV);
        for (float x : new float[] { x0 + 1.5F, x1 - 1.5F }) {
            for (float z : new float[] { c - r - 0.5F, c + r + 0.5F }) {
                plateBox(x - 1, 0, z - 1, x + 1, deck - 1.5F, z + 1, Mat.GALV);
            }
        }
        frame.add(new float[] { x0, 0, c - r - 2, x1, deck, c + r + 2 });
        for (int i = -1; i <= 1; i++) {
            float x = c + i * sp;
            turned(x, c, new float[][] { { deck, r }, { deck + h - 1.0F, r }, { deck + h - 1.0F, r + 0.5F }, { deck + h, r + 0.5F } }, 8, Ramp.CAN, true,
                    false);
            frame.add(new float[] { x - r - 0.5F, deck, c - r - 0.5F, x + r + 0.5F, deck + h, c + r + 0.5F });
            bushing(tier, x, deck + h, regBushingZ(tier, -1), true);
            bushing(tier, x, deck + h, regBushingZ(tier, 1), true);
            // The position indicator: a dial on the tank's east side.
            float dy = deck + h * 0.7F;
            swatchBox(x + r - 0.2F, dy - 1.5F, c - 1.5F, x + r + 0.6F, dy + 1.5F, c + 1.5F, BLACK);
            swatchBox(x + r + 0.6F, dy - 0.2F, c - 1.2F, x + r + 0.8F, dy + 0.2F, c + 0.3F, MARKER);
        }
        // The control cabinet beside the platform's east end, on its own pedestal, bracketed to the platform.
        plateBox(x1 + 1.2F, 0, c - 1, x1 + 2.8F, deck + 1, c + 1, Mat.GALV);
        plateBox(x1 + 0.6F, 0, c - 2, x1 + 3.4F, 0.6F, c + 2, Mat.GALV);
        plateBox(x1 - 0.2F, deck - 1.2F, c - 0.5F, x1 + 1.2F, deck - 0.2F, c + 0.5F, Mat.GALV);
        plateBox(x1, deck + 1, c - 3, x1 + 4, deck + 9, c + 3, Mat.GALV);
        frame.add(new float[] { x1, 0, c - 3, x1 + 4, deck + 9, c + 3 });
    }

    // Relay Panel: a galvanized control cabinet on a plinth, its door on the north face with two meters, the trip and
    // close lamps (red, green), a selector and a handle.
    private void relayPanel() {
        float c = 8;
        concrete(c - 7, 0, c - 5, c + 7, 1, c + 5);
        plateBox(c - 6, 1, c - 4, c + 6, 27, c + 4, Mat.GALV);
        plateBox(c - 6.5F, 27, c - 4.5F, c + 6.5F, 28, c + 4.5F, Mat.GALV);
        float z = c - 4;
        swatchBox(c - 5.0F, 3, z - 0.3F, c - 4.6F, 25, z, GALV0 + 2);
        for (float[] m : new float[][] { { c - 3, 18 }, { c + 2, 18 } }) {
            swatchBox(m[0] - 2, m[1] - 2, z - 0.4F, m[0] + 2, m[1] + 2, z, BLACK);
            swatchBox(m[0] - 0.2F, m[1] - 1.5F, z - 0.6F, m[0] + 0.2F, m[1] + 0.5F, z - 0.4F, MARKER);
        }
        swatchBox(c - 3.4F, 12, z - 0.5F, c - 2.2F, 13.2F, z, RED);
        swatchBox(c - 1.0F, 12, z - 0.5F, c + 0.2F, 13.2F, z, MARKER);
        swatchBox(c + 1.8F, 11.6F, z - 0.6F, c + 3.6F, 13.6F, z, BLACK);
        swatchBox(c + 4.0F, 7, z - 0.8F, c + 4.8F, 11, z, GALV0 + 7);
        frame.add(new float[] { c - 7, 0, c - 5, c + 7, 28, c + 5 });
    }

    // Ground Switch (MV, HV, EHV): a galvanized base, per phase a post of its tier's insulator (its top the line's
    // terminal) and a grounding blade hinged at the base on the south: up against the post's top when closed (the line
    // grounded), lying back along the base when open. A copper lead bonds each hinge to the base.
    public static final float GROUND_BASE = 2, GROUND_HINGE_Z = 6;

    private void groundSwitch(int tier, boolean open) {
        float c = 8, sp = DISCONNECT_SPACING[tier], base = GROUND_BASE;
        float reach = bushingHeight(tier) + 2;
        plateBox(c - sp - 3.5F, 0, c - 4, c + sp + 3.5F, base, c + GROUND_HINGE_Z + reach + 1, Mat.GALV);
        frame.add(new float[] { c - sp - 3.5F, 0, c - 4, c + sp + 3.5F, base, c + GROUND_HINGE_Z + reach + 1 });
        for (int i = -1; i <= 1; i++) {
            float x = c + i * sp;
            float top = bushing(tier, x, base, c, true);
            float[] hinge = { x, base + 1.2F, c + GROUND_HINGE_Z };
            swatchBox(x - 1.2F, base, c + GROUND_HINGE_Z - 1.2F, x + 1.2F, base + 2.0F, c + GROUND_HINGE_Z + 1.2F, GALV0 + 7);
            float[] end = open ? new float[] { x, base + 1.2F, c + GROUND_HINGE_Z + reach } : new float[] { x, top - 0.8F, c + 1.6F };
            member(hinge, end, 1.2F, 0.7F, new float[] { 1, 0, 0 }, Mat.GALV, true);
            jumper(new float[] { x + 1.4F, base + 1.0F, c + GROUND_HINGE_Z }, new float[] { x + 1.4F, base + 0.4F, c + GROUND_HINGE_Z + 2.5F });
        }
    }

    // How tall a bushing of a tier stands (a unit's size).
    private static float bushingHeight(int tier) {
        return switch (tier) {
            case 0 -> 7.6F;
            case 1 -> 10;
            case 2 -> 1 + 3 * 3 + 1;
            default -> 1 + 3 * 4 + 1;
        };
    }

    // Shunt Capacitor Bank (MV, HV): a galvanized rack with shelves of capacitor units (plated steel cans, two small
    // porcelain bushings each), a row per shelf, two units a phase; standing on its top shelf, a bushing of its tier per
    // phase (A B C west to east), the bank's terminals.
    public static final float[] CAP_SHELF = { 0, 10, 12, 0 };
    public static final int[] CAP_ROWS = { 0, 2, 3, 0 };
    public static final float CAP_SPACING = 12, CAP_HALF_Z = 5;

    // The top shelf's top, where the bushings stand.
    public static float capTop(int tier) {
        return 2 + CAP_ROWS[tier] * CAP_SHELF[tier];
    }

    private void capacitorBank(int tier) {
        float c = 8, shelf = CAP_SHELF[tier], top = capTop(tier), hx = CAP_SPACING + 6;
        for (float x : new float[] { c - hx, c + hx }) {
            for (float z : new float[] { c - CAP_HALF_Z, c + CAP_HALF_Z }) {
                member(new float[] { x, 0, z }, new float[] { x, top, z }, 1.6F, 1.6F, new float[] { 1, 0, 0 }, Mat.GALV, true);
            }
        }
        for (int row = 0; row <= CAP_ROWS[tier]; row++) {
            float y = 2 + row * shelf;
            plateBox(c - hx, y - 1, c - CAP_HALF_Z, c + hx, y, c + CAP_HALF_Z, Mat.GALV);
            if (row == CAP_ROWS[tier]) {
                break;
            }
            for (int i = -1; i <= 1; i++) {
                for (float dx : new float[] { -2.8F, 2.8F }) {
                    float x = c + i * CAP_SPACING + dx;
                    plateBox(x - 2.2F, y, c - 3.2F, x + 2.2F, y + shelf - 3.0F, c + 3.2F, Mat.GALV);
                    for (float dz : new float[] { -1.5F, 1.5F }) {
                        swatchBox(x - 0.6F, y + shelf - 3.0F, c + dz - 0.6F, x + 0.6F, y + shelf - 1.6F, c + dz + 0.6F, PORC0 + 3);
                    }
                }
            }
        }
        frame.add(new float[] { c - hx - 0.8F, 0, c - CAP_HALF_Z - 0.8F, c + hx + 0.8F, Math.max(top, 2 + CAP_ROWS[tier] * shelf), c + CAP_HALF_Z + 0.8F });
        for (int i = -1; i <= 1; i++) {
            bushing(tier, c + i * CAP_SPACING, top, c, true);
        }
    }

    // Shunt Reactor (HV, EHV): an oil-filled tank on a concrete pad with a radiator bank on its east side, a small
    // conservator, and three bushings of its tier in a row across the lid (A B C west to east).
    public static final float[] REACTOR_HALF_X = { 0, 0, 12, 16 }, REACTOR_HALF_Z = { 0, 0, 9, 12 }, REACTOR_H = { 0, 0, 26, 34 }, REACTOR_SPACING = { 0, 0, 8,
            10 };

    public static float reactorTop(int tier) {
        return Substation.PAD + REACTOR_H[tier] + Substation.LID;
    }

    private void reactor(int tier) {
        float c = 8, hx = REACTOR_HALF_X[tier], hz = REACTOR_HALF_Z[tier], y0 = Substation.PAD, y1 = y0 + REACTOR_H[tier], top = reactorTop(tier);
        concrete(c - hx - 10, 0, c - hz - 4, c + hx + 10, y0, c + hz + 4);
        plateBox(c - hx, y0, c - hz, c + hx, y1, c + hz, Mat.GALV);
        plateBox(c - hx - 1, y1, c - hz - 1, c + hx + 1, top, c + hz + 1, Mat.GALV);
        frame.add(new float[] { c - hx - 10, 0, c - hz - 4, c + hx + 10, y0, c + hz + 4 });
        frame.add(new float[] { c - hx - 1, y0, c - hz - 1, c + hx + 1, top, c + hz + 1 });
        float inner = c + hx, outer = inner + 7;
        for (float z = c - hz + 2.5F; z <= c + hz - 2.5F; z += 3) {
            plateBox(inner, y0 + 3, z - 0.5F, outer, y1 - 3, z + 0.5F, Mat.GALV);
        }
        frame.add(new float[] { inner, y0 + 3, c - hz + 1.5F, outer, y1 - 3, c + hz - 1.5F });
        float r = 3, cy = top + 3 + r;
        member(new float[] { c - hx * 0.5F, cy, c + hz - 3 }, new float[] { c + hx * 0.5F, cy, c + hz - 3 }, 2 * r, 2 * r, UP, Mat.GALV, true);
        for (float x : new float[] { c - hx * 0.3F, c + hx * 0.3F }) {
            plateBox(x - 1, top, c + hz - 4, x + 1, cy - r + 0.5F, c + hz - 2, Mat.GALV);
        }
        frame.add(new float[] { c - hx * 0.5F, top, c + hz - 3 - r, c + hx * 0.5F, cy + r, c + hz - 3 + r });
        for (int i = -1; i <= 1; i++) {
            bushing(tier, c + i * REACTOR_SPACING[tier], top, c - 2, false);
        }
    }

    // HV Dead-End or Transposition Tower: the H-frame's two poles, a crossbeam on each face (north and south), each phase
    // dead-ended both ways on glass strain strings with its jumper under the beams. On the transposition tower the
    // jumpers cross: the conductor that comes in at one place leaves a place over (west to middle, middle to east, east
    // to west), so the phases rotate along the line.
    public static final int DEAD_END_HV_DISCS = 6;
    public static final float DEAD_END_HV_RIM = 3.0F, DEAD_END_HV_BEAM = 282;
    public static final float[] DEAD_END_HV_X = { -42, 8, 58 };

    public static float deadEndHvNorth() {
        return 8 - 4.85F + 0.5F - 6.0F;
    }

    public static float deadEndHvSouth() {
        return 8 + 4.85F - 0.5F + 6.0F;
    }

    private void deadEndHv(boolean transpose) {
        float cz = 8, top = 316, lx = -24, rx = 40, r = 4.85F, beamY = DEAD_END_HV_BEAM;
        pole(lx, cz, 0, top, 10, 3.0F, true, 40);
        pole(rx, cz, 0, top, 10, 3.0F, true, 150);
        float north = deadEndHvNorth(), south = deadEndHvSouth();
        timber(-48, beamY, north, 64, beamY + 7, cz - r + 0.5F);
        timber(-48, beamY, cz + r - 0.5F, 64, beamY + 7, south);
        for (float px : new float[] { lx, rx }) {
            swatchBox(px - 0.7F, beamY + 2.5F, north - 0.6F, px + 0.7F, beamY + 4.0F, south + 0.6F, GALV0 + 7);
            vBrace(px, beamY - 24, cz + r + 0.4F, beamY, south - 3.0F, 14.0F, 1.8F, 1.0F, true, true);
        }
        float y = beamY + 3.5F;
        float[] ends = new float[6];
        for (int k = 0; k < 3; k++) {
            ends[k] = glassStrain(DEAD_END_HV_X[k], y, north, -1, DEAD_END_HV_DISCS, DEAD_END_HV_RIM);
            ends[3 + k] = glassStrain(DEAD_END_HV_X[k], y, south, 1, DEAD_END_HV_DISCS, DEAD_END_HV_RIM);
        }
        for (int k = 0; k < 3; k++) {
            int to = transpose ? (k + 1) % 3 : k;
            float xn = DEAD_END_HV_X[k], xs = DEAD_END_HV_X[to], drop = beamY - 10 - 5 * k;
            jumper(new float[] { xn, y - 1.4F, ends[k] }, new float[] { xn, drop, ends[k] }, new float[] { xs, drop, ends[3 + to] },
                    new float[] { xs, y - 1.4F, ends[3 + to] });
        }
        // The X-brace between the poles, in their plane.
        float y0 = 140, y1 = 250;
        float[] mid = { (lx + rx) / 2.0F, (y0 + y1) / 2.0F, cz };
        for (float[] end : new float[][] { { lx + 2.2F, y0 }, { lx + 2.2F, y1 }, { rx - 2.2F, y0 }, { rx - 2.2F, y1 } }) {
            float[] from = { end[0], end[1], cz };
            member(from, add(mid, scale(normalize(sub(from, mid)), 1.6F)), 2.0F, 2.0F, new float[] { 0, 0, 1 }, Mat.GALV, true);
        }
        plateBox(mid[0] - 2.0F, mid[1] - 2.0F, cz - 1.5F, mid[0] + 2.0F, mid[1] + 2.0F, cz + 1.5F, Mat.GALV);
        shieldPeak(lx, top, cz, 18);
        shieldPeak(rx, top, cz, 18);
    }

    // The EHV lattice's dead-end strings at an arm tip: a strain string each way off the tip's end frame, and the jumper
    // (the lattice's dead-end and transposition towers).
    public static final int DEAD_END_EHV_DISCS = 8;
    public static final float DEAD_END_EHV_RIM = 4.0F, DEAD_END_EHV_FACE = 3.9F;

    // Riser (LV, MV): the tier's pole with its crossarm(s) and, where its line insulators would be, cable terminations
    // (porcelain potheads, their tops the overhead terminals); the cables run from their feet down a galvanized conduit
    // on the pole's south face to a cable box at its foot, where the underground cable joins (the cable terminals on
    // the box's top).
    public static final float RISER_BOX_Y = 4, RISER_CONDUIT_Z = 8 + 4.2F;

    private void riserLv() {
        float cx = 8, cz = 8, top = 150, armY = 132, armZ0 = 10.0F, armZ1 = 13.0F, z = (armZ0 + armZ1) / 2;
        pole(cx, cz, 0, top, 8, 2.0F, false, 140);
        timber(-12, armY, armZ0, 28, armY + 4, armZ1);
        swatchBox(7.5F, armY + 1.5F, 7.0F, 8.5F, armY + 2.5F, armZ1 + 0.6F, GALV0 + 7);
        vBrace(cx, armY - 18, 10.7F, armY, 11.5F, 10.0F, 1.2F, 0.6F, true, true);
        riserDown(new float[] { -8, 1, 24 }, armY + 4, z, armY, 2.4F);
        tag(cx, 40, cz + 2.41F, true);
    }

    private void riserMv() {
        float cx = 8, cz = 8, top = 222, face = cz + 3.62F, upperY = 196, lowerY = 166;
        pole(cx, cz, 0, top, 8, 3.0F, true, 260);
        timber(-12, upperY, face - 0.4F, 14, upperY + 4, face + 3.6F);
        timber(-20, lowerY, face - 0.4F, 36, lowerY + 4, face + 3.6F);
        for (float armY : new float[] { upperY, lowerY }) {
            swatchBox(7.4F, armY + 1.4F, cz - 4.2F, 8.6F, armY + 2.6F, face + 4.2F, GALV0 + 7);
        }
        vBrace(cx, upperY - 20, face + 0.6F, upperY, face + 1.6F, 14.0F, 1.6F, 0.8F, true, false);
        vBrace(cx, lowerY - 20, face + 0.6F, lowerY, face + 1.6F, 16.0F, 1.6F, 0.8F, true, true);
        float az = face + 1.6F;
        postInsulator(-8, upperY + 4, az, 12);
        postInsulator(-16, lowerY + 4, az, 12);
        postInsulator(32, lowerY + 4, az, 12);
        // Each termination's cable down from its foot to the conduit's head under the lower arm.
        float head = lowerY - 4, conduitZ = face + 4.6F;
        for (float[] p : new float[][] { { -8, upperY + 4 }, { -16, lowerY + 4 }, { 32, lowerY + 4 } }) {
            rod(new float[] { p[0], p[1] - 0.4F, az }, new float[] { p[0], p[1] - 3.0F, az }, 1.2F, BLACK);
            rod(new float[] { p[0], p[1] - 3.0F, az }, new float[] { cx, head, conduitZ }, 1.2F, BLACK);
        }
        conduit(cx, head, conduitZ);
        shieldPeak(cx, top, cz, 16);
    }

    // LV: potheads at xs on the arm (y, z), cables to the conduit's head below the arm, the conduit down to the box.
    private void riserDown(float[] xs, float y, float z, float armY, float headDrop) {
        float head = armY - 6, conduitZ = RISER_CONDUIT_Z;
        for (float x : xs) {
            postInsulator(x, y, z, 7.6F);
            rod(new float[] { x, y - 0.4F, z }, new float[] { x, y - headDrop, z }, 1.0F, BLACK);
            rod(new float[] { x, y - headDrop, z }, new float[] { 8, head, conduitZ }, 1.0F, BLACK);
        }
        conduit(8, head, conduitZ);
    }

    // The conduit: a galvanized pipe from its head down a pole's face to the cable box at its foot, strapped.
    private void conduit(float x, float head, float z) {
        member(new float[] { x, RISER_BOX_Y, z }, new float[] { x, head + 1, z }, 2.0F, 2.0F, new float[] { 0, 0, 1 }, Mat.GALV, true);
        swatchBox(x - 1.4F, head + 1, z - 1.4F, x + 1.4F, head + 2.4F, z + 1.4F, GALV0 + 6);
        for (float y = 20; y < head; y += 24) {
            swatchBox(x - 1.4F, y, z - 1.2F, x + 1.4F, y + 0.6F, z + 1.4F, GALV0 + 7);
        }
        plateBox(x - 4, 0, z - 1.5F, x + 4, RISER_BOX_Y, z + 2.5F, Mat.GALV);
        frame.add(new float[] { x - 4, 0, z - 1.5F, x + 4, RISER_BOX_Y, z + 2.5F });
        frame.add(new float[] { x - 1, RISER_BOX_Y, z - 1, x + 1, head + 2.4F, z + 1 });
    }

    // Riser (HV, EHV): a galvanized stand (four legs and a deck), on it three outdoor cable terminations (tall porcelain
    // with sheds on a stress-cone base, its top the overhead terminal), each cable dropping through the deck to a cable
    // box on the ground (the cable terminal on its top).
    public static final float[] RISER_DECK = { 0, 0, 20, 28 }, RISER_POST = { 0, 0, 22, 32 }, RISER_SPACING = { 0, 0, 12, 16 };

    private void riserStand(int tier) {
        float c = 8, deck = RISER_DECK[tier], sp = RISER_SPACING[tier], hx = sp + 5, hz = 5;
        for (float x : new float[] { c - hx, c + hx }) {
            for (float z : new float[] { c - hz, c + hz }) {
                concrete(x - 2, 0, z - 2, x + 2, 1, z + 2);
                member(new float[] { x, 1, z }, new float[] { x, deck, z }, 2.0F, 2.0F, new float[] { 1, 0, 0 }, Mat.GALV, true);
            }
        }
        plateBox(c - hx - 1, deck, c - hz - 1, c + hx + 1, deck + 1.5F, c + hz + 1, Mat.GALV);
        frame.add(new float[] { c - hx - 1, 0, c - hz - 1, c + hx + 1, deck + 1.5F, c + hz + 1 });
        for (int i = -1; i <= 1; i++) {
            float x = c + i * sp;
            turned(x, c, new float[][] { { deck + 1.5F, 3.0F }, { deck + 5.0F, 3.0F }, { deck + 7.0F, 1.8F } }, 8, Ramp.CAN, false, false);
            postInsulator(x, deck + 7.0F, c, RISER_POST[tier], Ramp.GLASS);
            rod(new float[] { x, deck, c }, new float[] { x, RISER_BOX_Y, c }, 1.6F, BLACK);
            plateBox(x - 2.5F, 0, c - 2.5F, x + 2.5F, RISER_BOX_Y, c + 2.5F, Mat.GALV);
        }
    }

    // Sectionalizer (MV): a small galvanized tank on feet with three bushings each side (line north, load south), a
    // counter cabinet with its yellow band and window on the east, and its position flag (red closed, green open).
    private void sectionalizer(boolean open) {
        Breaker b = new Breaker(1, 6, 6, 8, 6.0F);
        float c = 8, y0 = Breaker.FEET, y1 = y0 + b.height();
        for (float x : new float[] { c - b.halfX() + 1, c + b.halfX() - 3 }) {
            plateBox(x, 0, c - b.halfZ() + 1, x + 2, y0, c + b.halfZ() - 1, Mat.GALV);
        }
        turned(c, c, new float[][] { { y0, b.halfX() - 0.5F }, { y1, b.halfX() - 0.5F } }, 8, Ramp.CAN, true, true);
        plateBox(c - b.halfX() - 0.5F, y1, c - b.halfZ() - 0.5F, c + b.halfX() + 0.5F, b.top(), c + b.halfZ() + 0.5F, Mat.GALV);
        plateBox(c + b.halfX(), y0 + 1, c - 3, c + b.halfX() + 3, y1 - 1, c + 3, Mat.GALV);
        swatchBox(c + b.halfX() + 3, y0 + 2, c - 3, c + b.halfX() + 3.4F, y0 + 3, c + 3, HAZARD);
        swatchBox(c + b.halfX() + 3, y1 - 4, c - 1.5F, c + b.halfX() + 3.4F, y1 - 2.4F, c + 1.5F, BLACK);
        swatchBox(c + b.halfX() + 3, y0 + 4, c - 0.8F, c + b.halfX() + 3.6F, y0 + 5.6F, c + 0.8F, open ? MARKER : RED);
        frame.add(new float[] { c - b.halfX() - 0.5F, 0, c - b.halfZ() - 0.5F, c + b.halfX() + 3.6F, b.top(), c + b.halfZ() + 0.5F });
        for (int i = -1; i <= 1; i++) {
            bushing(1, c + i * b.spacing(), b.top(), c - b.halfZ() + 2.5F, true);
            bushing(1, c + i * b.spacing(), b.top(), c + b.halfZ() - 2.5F, true);
        }
    }

    // Automatic Transfer Switch (LV, MV): a galvanized pad-mounted cabinet (plated like the transformers' tanks, its lid a
    // plate over it) on a concrete pad with three rows of bushings on its
    // roof: the preferred feeder (north row), the load (south row) and the alternate feeder (middle row), each A B C west
    // to east; two position lamps on its front (green: on the preferred; amber: on the alternate).
    public static final float[] ATS_HALF = { 9, 12, 0, 0 }, ATS_H = { 12, 16, 0, 0 }, ATS_SPACING = { 6.0F, 7.5F, 0, 0 };

    public static float atsRowZ(int tier, int row) {
        return 8 + row * (ATS_HALF[tier] - 2.5F);
    }

    private void ats(int tier, boolean alternate) {
        float c = 8, h = ATS_HALF[tier], height = ATS_H[tier];
        concrete(c - h - 2, 0, c - h - 2, c + h + 2, 1, c + h + 2);
        plateBox(c - h, 1, c - h, c + h, 1 + height, c + h, Mat.GALV);
        plateBox(c - h - 0.5F, 1 + height, c - h - 0.5F, c + h + 0.5F, 1.6F + height, c + h + 0.5F, Mat.GALV);
        swatchBox(c - 3, height - 3, c - h - 0.6F, c - 1.6F, height - 1.6F, c - h - 0.3F, alternate ? BLACK : MARKER);
        swatchBox(c + 1.6F, height - 3, c - h - 0.6F, c + 3, height - 1.6F, c - h - 0.3F, alternate ? HAZARD : BLACK);
        frame.add(new float[] { c - h - 2, 0, c - h - 2, c + h + 2, 1.6F + height, c + h + 2 });
        float roof = 1.6F + height;
        for (int row = -1; row <= 1; row++) {
            for (int i = -1; i <= 1; i++) {
                bushing(tier, c + i * ATS_SPACING[tier], roof, atsRowZ(tier, row), true);
            }
        }
    }

    // ================================================================================================================
    // Building blocks
    // ================================================================================================================

    // A member from a to b (its centre line), w px across and d px deep (depth along depthHint, squared to the axis),
    // each long face plated in m with its grain along the member, its ends capped when caps is set. Its solid boxes go
    // in steps of 2 px along it (a slanted member as a stair).
    private void member(float[] a, float[] b, float w, float d, float[] depthHint, Mat m, boolean caps) {
        float[] axis = normalize(sub(b, a));
        float[] e = sub(depthHint, scale(axis, dot(depthHint, axis)));
        if (length(e) < 1.0E-4F) {
            e = Math.abs(axis[1]) < 0.9F ? sub(UP, scale(axis, axis[1])) : new float[] { 1, 0, 0 };
        }
        e = normalize(e);
        float[] f = normalize(cross(axis, e));
        float[] he = scale(e, d / 2), hf = scale(f, w / 2);
        float[] a00 = add(add(a, he), hf), a01 = add(sub(a, he), hf), a11 = sub(sub(a, he), hf), a10 = sub(add(a, he), hf);
        float[] b00 = add(add(b, he), hf), b01 = add(sub(b, he), hf), b11 = sub(sub(b, he), hf), b10 = sub(add(b, he), hf);
        // The four long faces: +e, -e, +f, -f.
        face(List.of(a10, a00, b00, b10), e, axis, m);
        face(List.of(a01, a11, b11, b01), scale(e, -1), axis, m);
        face(List.of(a00, a01, b01, b00), f, axis, m);
        face(List.of(a11, a10, b10, b11), scale(f, -1), axis, m);
        if (caps) {
            face(List.of(a00, a10, a11, a01), scale(axis, -1), null, m);
            face(List.of(b00, b01, b11, b10), axis, null, m);
        }
        float len = length(sub(b, a));
        int steps = Math.abs(axis[0]) > 0.999F || Math.abs(axis[1]) > 0.999F || Math.abs(axis[2]) > 0.999F ? 1 : Math.max(1, (int) Math.ceil(len / 2.0F));
        for (int k = 0; k < steps; k++) {
            float[] p = add(a, scale(axis, len * k / steps)), q = add(a, scale(axis, len * (k + 1) / steps));
            float[] box = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
            for (float[] base : new float[][] { p, q }) {
                for (float[] corner : new float[][] { add(add(base, he), hf), add(sub(base, he), hf), sub(sub(base, he), hf), sub(add(base, he), hf) }) {
                    for (int i = 0; i < 3; i++) {
                        box[i] = Math.min(box[i], corner[i]);
                        box[i + 3] = Math.max(box[i + 3], corner[i]);
                    }
                }
            }
            boxes.add(box);
        }
    }

    // A galvanized bracing bar from a to b, cut back `inset` px at each end so it ends inside what it joins.
    private void brace(float[] a, float[] b, float inset, float w, float d, float[] depthHint, boolean caps) {
        float[] dir = normalize(sub(b, a));
        member(add(a, scale(dir, inset)), sub(b, scale(dir, inset)), w, d, depthHint, Mat.GALV, caps);
    }

    // A flat convex face (points in any winding; outward given) plated as one plate of m: its frame's up is the in-plane
    // direction nearest world up (or north, on a face looking up or down), kept along or across `along` when given so
    // the grain runs with the member; its window of the grain field and edge strips picked from where it is.
    private void face(List<float[]> points, float[] outward, float[] along, Mat m) {
        float[] n = normalize(outward);
        float[] ref = Math.abs(n[1]) > 0.9F ? new float[] { 0, 0, -1 } : UP;
        float[] up;
        if (along != null) {
            float[] a = normalize(sub(along, scale(n, dot(along, n))));
            float[] b = normalize(cross(n, a));
            up = a;
            float best = dot(a, ref);
            for (float[] c : new float[][] { scale(a, -1), b, scale(b, -1) }) {
                if (dot(c, ref) > best + 1.0E-4F) {
                    best = dot(c, ref);
                    up = c;
                }
            }
        } else {
            up = sub(ref, scale(n, dot(ref, n)));
            if (length(up) < 1.0E-4F) {
                up = Math.abs(n[0]) < 0.9F ? new float[] { 1, 0, 0 } : new float[] { 0, 0, 1 };
                up = sub(up, scale(n, dot(up, n)));
            }
            up = normalize(up);
        }
        float[] right = cross(up, n);
        float sMin = Float.MAX_VALUE, sMax = -Float.MAX_VALUE, tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
        for (float[] p : points) {
            sMin = Math.min(sMin, dot(p, right));
            sMax = Math.max(sMax, dot(p, right));
            tMin = Math.min(tMin, dot(p, up));
            tMax = Math.max(tMax, dot(p, up));
        }
        float depth = dot(points.get(0), n);
        float[] base = add(add(scale(n, depth), scale(right, sMin)), scale(up, tMin));
        List<float[]> st = new ArrayList<>();
        for (float[] p : points) {
            st.add(new float[] { dot(p, right) - sMin, dot(p, up) - tMin });
        }
        long seed = ++faceSeed * 0x9E3779B97F4A7C15L;
        for (float v : base) {
            seed = (seed ^ Math.round(v * 4.0F)) * 1099511628211L;
        }
        paintPlane(st, base, right, up, plateRects(sMax - sMin, tMax - tMin, m, seed));
    }

    // A face w x h px as plates of m: as few as keep each within the grain field, each its own window of the field at
    // 1:1 with a 1 px lit top row and left column, a dark bottom row and right column (from the edge strips, at the same
    // window), lit and dark corners and the corner tone where they meet. A strip under 2 px tall is a slice of the field;
    // ends get their columns only on faces 3 px or more along. Rectangles {s0, s1, t0, t1, u, v}, (u, v) at (s0, t1).
    static List<float[]> plateRects(float w, float h, Mat m, long seed) {
        List<float[]> rects = new ArrayList<>();
        int across = Math.max(1, (int) Math.ceil(w / m.fw - 1.0E-3F)), down = Math.max(1, (int) Math.ceil(h / m.fh - 1.0E-3F));
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < across; i++) {
            for (int j = 0; j < down; j++) {
                float s0 = w * i / across, s1 = w * (i + 1) / across, t0 = h * j / down, t1 = h * (j + 1) / down;
                float pw = s1 - s0, ph = t1 - t0;
                int cw = (int) Math.ceil(pw - 1.0E-3F), ch = (int) Math.ceil(ph - 1.0E-3F);
                int ox = random.nextInt(Math.max(1, m.fw - cw + 1)), oy = random.nextInt(Math.max(1, m.fh - ch + 1));
                boolean rows = ph >= 2.0F - 1.0E-3F, cols = rows && pw >= 3.0F - 1.0E-3F;
                float e = cols ? 1.0F : 0.0F, r = rows ? 1.0F : 0.0F;
                // The field.
                if (pw - 2 * e > 1.0E-3F && ph - 2 * r > 1.0E-3F) {
                    rects.add(new float[] { s0 + e, s1 - e, t0 + r, t1 - r, m.fx + ox + e, m.fy + oy + r });
                }
                if (rows) {
                    rects.add(new float[] { s0 + e, s1 - e, t1 - 1, t1, EDGE_X + ox + e, m.litRow });
                    rects.add(new float[] { s0 + e, s1 - e, t0, t0 + 1, EDGE_X + ox + e, m.darkRow });
                }
                if (cols) {
                    rects.add(new float[] { s0, s0 + 1, t0 + 1, t1 - 1, m.litCol, EDGE_COL_Y + oy + 1 });
                    rects.add(new float[] { s1 - 1, s1, t0 + 1, t1 - 1, m.darkCol, EDGE_COL_Y + oy + 1 });
                    rects.add(new float[] { s0, s0 + 1, t1 - 1, t1, EDGE_X + ox, m.litRow });
                    rects.add(new float[] { s1 - 1, s1, t0, t0 + 1, EDGE_X + ox + cw - 1, m.darkRow });
                    float[] corner = swatchTexel(m.corner);
                    rects.add(new float[] { s1 - 1, s1, t1 - 1, t1, corner[0], corner[1] });
                    rects.add(new float[] { s0, s0 + 1, t0, t0 + 1, corner[0], corner[1] });
                }
            }
        }
        return rects;
    }

    // A round part about the upright axis at (cx, cz): its profile {y, r} from the bottom up, `sides` facets, each band
    // between two profile points a frustum carrying the ramp round it (or across its length, on a band wider round than
    // it is long), each step in radius at one height a flat ring (lit swatch looking up, dark looking down), and flat
    // caps on the ends when asked.
    private void turned(float cx, float cz, float[][] profile, int sides, Ramp ramp, boolean capTop, boolean capBottom) {
        float rMax = 0;
        for (int k = 0; k + 1 < profile.length; k++) {
            float ya = profile[k][0], ra = profile[k][1], yb = profile[k + 1][0], rb = profile[k + 1][1];
            rMax = Math.max(rMax, Math.max(ra, rb));
            float[][] lo = ring(cx, cz, ra, sides), hi = ring(cx, cz, rb, sides);
            if (Math.abs(yb - ya) < 1.0E-4F) {
                if (Math.abs(rb - ra) > 1.0E-4F) {
                    annulus(cx, cz, ya, Math.min(ra, rb), Math.max(ra, rb), sides, rb < ra, ramp);
                }
                continue;
            }
            float round = (float) (2.0 * Math.PI * (ra + rb) / 2.0);
            for (int i = 0; i < sides; i++) {
                float[] p0 = { lo[(i + 1) % sides][0], ya, lo[(i + 1) % sides][2] }, p1 = { lo[i][0], ya, lo[i][2] };
                float[] p2 = { hi[i][0], yb, hi[i][2] }, p3 = { hi[(i + 1) % sides][0], yb, hi[(i + 1) % sides][2] };
                float fw = (length(sub(p1, p0)) + length(sub(p2, p3))) / 2.0F;
                float len = (length(sub(p3, p0)) + length(sub(p2, p1))) / 2.0F;
                if (fw < 1.0E-3F || len < 1.0E-3F) {
                    continue;
                }
                rampFacet(p0, p1, p2, p3, i, sides, fw, len, round, ramp);
            }
        }
        if (capTop) {
            float[] last = profile[profile.length - 1];
            if (last[1] > 1.0E-3F) {
                annulus(cx, cz, last[0], 0, last[1], sides, true, ramp);
            }
        }
        if (capBottom) {
            float[] first = profile[0];
            if (first[1] > 1.0E-3F) {
                annulus(cx, cz, first[0], 0, first[1], sides, false, ramp);
            }
        }
        float y0 = profile[0][0], y1 = profile[profile.length - 1][0];
        if (rMax > 1.5F && y1 - y0 > 1.0F) {
            boxes.add(new float[] { cx - rMax, Math.min(y0, y1), cz - rMax, cx + rMax, Math.max(y0, y1), cz + rMax });
        }
    }

    // A frustum facet (p0 p1 along its bottom, right to left round the part so right x up looks out; p3 p2 its top) with
    // the ramp across it at 1:1 (the facet's plane, its bottom edge as across, so a tapering facet keeps every texel
    // square): a facet 3 px or more round carries the whole ramp; narrower ones share one ramp that carries on round the
    // part (repeating so it's at most 14 px); a band wider round than it is long takes the ramp across its length
    // instead, the same all the way round.
    private void rampFacet(float[] p0, float[] p1, float[] p2, float[] p3, int i, int sides, float fw, float len, float round, Ramp ramp) {
        if (fw >= 3.0F && len < fw) {
            float v = (ramp.row + 1.5F) / SHEET_H;
            int period = Math.max(2, Math.min(14, Math.round(len)));
            float rx = RAMP_X + rampX(period);
            // Along its length: the ramp runs bottom (s = 0) to top, one tone a band.
            for (int k = 0; k < period; k++) {
                float t0 = k / (float) period, t1 = (k + 1) / (float) period;
                float u = (rx + period - 1 - k + 0.5F) / SHEET_W;
                strip(p0, p1, p2, p3, 0, 1, t0, t1, u, u, v);
            }
            return;
        }
        float bottom = length(sub(p1, p0));
        int period;
        float s0;
        if (fw >= 3.0F) {
            period = Math.round(bottom);
            s0 = 0;
        } else {
            period = Math.round(round / (float) Math.ceil(round / 14.0F));
            s0 = (sides - 1 - i) * fw;
        }
        period = Math.max(2, Math.min(14, period));
        float rx = RAMP_X + rampX(period);
        // The facet's plane: across along its bottom edge, up square to it; its points' place in it.
        float[] right = normalize(sub(p1, p0));
        float[] rise = sub(p3, p0);
        float[] up = normalize(sub(rise, scale(right, dot(rise, right))));
        List<float[]> st = new ArrayList<>();
        float height = 0;
        for (float[] p : new float[][] { p0, p1, p2, p3 }) {
            float[] d = sub(p, p0);
            st.add(new float[] { s0 + dot(d, right), dot(d, up) });
            height = Math.max(height, dot(d, up));
        }
        float sMin = Float.MAX_VALUE, sMax = -Float.MAX_VALUE;
        for (float[] q : st) {
            sMin = Math.min(sMin, q[0]);
            sMax = Math.max(sMax, q[0]);
        }
        List<float[]> rects = new ArrayList<>();
        for (float k = (float) Math.floor(sMin / period) * period; k < sMax - 1.0E-4F; k += period) {
            for (float t = 0; t < height - 1.0E-4F; t += RAMP_H) {
                rects.add(new float[] { k, k + period, t, Math.min(height, t + RAMP_H), rx, ramp.row });
            }
        }
        // The plane's base is where s = 0 would be along the bottom edge (s0 back from p0).
        paintPlane(st, sub(p0, scale(right, s0)), right, up, rects);
    }

    // The part of a facet from fraction a to b across and c to d up, its texels u0 to u1 across on row v.
    private void strip(float[] p0, float[] p1, float[] p2, float[] p3, float a, float b, float c, float d, float u0, float u1, float v) {
        if (b - a < 1.0E-5F || d - c < 1.0E-5F) {
            return;
        }
        float[] bl = bilinear(p0, p1, p2, p3, a, c), br = bilinear(p0, p1, p2, p3, b, c), tr = bilinear(p0, p1, p2, p3, b, d),
                tl = bilinear(p0, p1, p2, p3, a, d);
        quad(bl, br, tr, tl, new float[] { u0, v, u1, v, u1, v, u0, v });
    }

    // The glazed top of a disc: a cone from (rim radius at yRim) up to (cap radius at yCap), each facet showing the disc
    // ramp from rim (bottom) to cap at 1:1 (the facet's own plane, its rim edge as across).
    private void coneTop(float cx, float cz, float yRim, float rRim, float yCap, float rCap, int sides, int rampX) {
        float[][] lo = ring(cx, cz, rRim, sides), hi = ring(cx, cz, rCap, sides);
        for (int i = 0; i < sides; i++) {
            float[] p0 = { lo[(i + 1) % sides][0], yRim, lo[(i + 1) % sides][2] }, p1 = { lo[i][0], yRim, lo[i][2] };
            float[] p2 = { hi[i][0], yCap, hi[i][2] }, p3 = { hi[(i + 1) % sides][0], yCap, hi[(i + 1) % sides][2] };
            float[] right = normalize(sub(p1, p0));
            float[] rise = sub(p3, p0);
            float[] up = normalize(sub(rise, scale(right, dot(rise, right))));
            List<float[]> st = new ArrayList<>();
            float height = 0, width = 0;
            for (float[] p : new float[][] { p0, p1, p2, p3 }) {
                float[] d = sub(p, p0);
                st.add(new float[] { dot(d, right), dot(d, up) });
                height = Math.max(height, dot(d, up));
                width = Math.max(width, dot(d, right));
            }
            // Rows from the cap (the strip's top row) down to the rim.
            paintPlane(st, p0, right, up, List.of(new float[] { 0, Math.min(8.0F, width), 0, Math.min(8.0F, height), rampX, DISC_Y }));
        }
    }

    // A flat ring (a disc when inner is 0) across the upright axis at y, looking up or down, in the ramp's lit or dark
    // end as one tone (a small round face; caps on the big parts carry modules instead).
    private void annulus(float cx, float cz, float y, float inner, float outer, int sides, boolean up, Ramp ramp) {
        float[][] o = ring(cx, cz, outer, sides), in = ring(cx, cz, inner, sides);
        int tone = switch (ramp) {
            case GALV -> up ? GALV0 + 7 : GALV0 + 3;
            case PORCELAIN -> up ? PORC0 + 4 : PORC0 + 1;
            case GLASS -> up ? GLASS0 + 4 : GLASS0 + 1;
            case CAN -> up ? CAN0 + 4 : CAN0 + 2;
            case COPPER -> COPPER0 + 2;
        };
        float[] uv = swatchUv(tone);
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            float[] a = { o[i][0], y, o[i][2] }, b = { o[j][0], y, o[j][2] }, c = { in[j][0], y, in[j][2] }, d = { in[i][0], y, in[i][2] };
            // Seen from above, the angle rises counter-clockwise when x is right and z is down: a b c d is clockwise
            // from above, so it looks down; reversed it looks up.
            if (up) {
                quad(d, c, b, a, uv);
            } else {
                quad(a, b, c, d, uv);
            }
        }
    }

    // An axis-aligned box in one flat tone (a small part: a bolt, a staple, a clamp, a barb).
    private void swatchBox(float x0, float y0, float z0, float x1, float y1, float z1, int swatch) {
        float[] uv = swatchUv(swatch);
        float[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y1, z0 }, { x0, y1, z0 }, { x0, y0, z1 }, { x1, y0, z1 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[][] faces = { { 1, 0, 3, 2 }, { 4, 5, 6, 7 }, { 0, 4, 7, 3 }, { 5, 1, 2, 6 }, { 3, 7, 6, 2 }, { 0, 1, 5, 4 } };
        for (int[] f : faces) {
            quad(c[f[0]], c[f[1]], c[f[2]], c[f[3]], uv);
        }
    }

    // The same, leaving out one face (1 north, 2 south), where a painted plane stands in for it.
    private void swatchBoxOpen(float x0, float y0, float z0, float x1, float y1, float z1, int swatch, int open) {
        float[] uv = swatchUv(swatch);
        float[][] c = { { x0, y0, z0 }, { x1, y0, z0 }, { x1, y1, z0 }, { x0, y1, z0 }, { x0, y0, z1 }, { x1, y0, z1 }, { x1, y1, z1 }, { x0, y1, z1 } };
        int[][] faces = { { 1, 0, 3, 2 }, { 4, 5, 6, 7 }, { 0, 4, 7, 3 }, { 5, 1, 2, 6 }, { 3, 7, 6, 2 }, { 0, 1, 5, 4 } };
        for (int k = 0; k < faces.length; k++) {
            if (k + 1 == open) {
                continue;
            }
            int[] f = faces[k];
            quad(c[f[0]], c[f[1]], c[f[2]], c[f[3]], uv);
        }
    }

    static float[] swatchTexel(int index) {
        return new float[] { 64 + 4 * (index % 32) + 1, 224 + 4 * (index / 32) + 1 };
    }

    static float[] swatchUv(int index) {
        float u = (64 + 4 * (index % 32) + 2) / (float) SHEET_W, v = (224 + 4 * (index / 32) + 2) / (float) SHEET_H;
        return new float[] { u, v, u, v, u, v, u, v };
    }

    private static float[] add(float[] a, float[] b) {
        return new float[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    private static float[] scale(float[] a, float s) {
        return new float[] { a[0] * s, a[1] * s, a[2] * s };
    }

    private static float[] midpoint(float[] a, float[] b) {
        return new float[] { (a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2 };
    }

    // ================================================================================================================
    // Placing the model
    // ================================================================================================================

    // The model's vertex data turned `turns` quarter turns clockwise seen from above (north to east) about the centre
    // column's middle, normals with it, for a tower facing that way (StaticMeshes only draws poses that don't turn).
    public static float[] turned(float[] data, int turns) {
        turns = Math.floorMod(turns, 4);
        if (turns == 0) {
            return data;
        }
        float[] out = data.clone();
        for (int i = 0; i + 7 < out.length; i += 8) {
            for (int t = 0; t < turns; t++) {
                // (x, z) -> (1 - z, x) about the block's middle (0.5, 0.5); normals (nx, nz) -> (-nz, nx).
                float x = out[i], z = out[i + 2];
                out[i] = 1.0F - z;
                out[i + 2] = x;
                float nx = out[i + 5], nz = out[i + 7];
                out[i + 5] = -nz;
                out[i + 7] = nx;
            }
        }
        return out;
    }

    // A point in the model's frame (px) turned the same way, in blocks from the centre column's base block's corner.
    // A model's vertices turned `eighths` eighths of a turn clockwise (seen from above) about the centre column's middle:
    // quarter turns exactly as turned(), an odd eighth by 45 degrees more (positions and normals).
    public static float[] turned8(float[] data, int eighths) {
        int e = Math.floorMod(eighths, 8);
        float[] out = turned(data, e / 2);
        if (e % 2 == 0) {
            return out;
        }
        out = out == data ? data.clone() : out;
        float c = (float) Math.cos(Math.PI / 4), sn = (float) Math.sin(Math.PI / 4);
        for (int i = 0; i + 7 < out.length; i += 8) {
            float dx = out[i] - 0.5F, dz = out[i + 2] - 0.5F;
            out[i] = 0.5F + dx * c - dz * sn;
            out[i + 2] = 0.5F + dx * sn + dz * c;
            float nx = out[i + 5], nz = out[i + 7];
            out[i + 5] = nx * c - nz * sn;
            out[i + 7] = nx * sn + nz * c;
        }
        return out;
    }

    // A px point turned `eighths` eighths of a turn about the centre column's middle, in blocks (as turnedPoint).
    public static double[] turnedPoint8(float x, float y, float z, int eighths) {
        int e = Math.floorMod(eighths, 8);
        double[] q = turnedPoint(x, y, z, e / 2);
        if (e % 2 == 0) {
            return q;
        }
        double c = Math.cos(Math.PI / 4), sn = Math.sin(Math.PI / 4), dx = q[0] - 0.5, dz = q[2] - 0.5;
        return new double[] { 0.5 + dx * c - dz * sn, q[1], 0.5 + dx * sn + dz * c };
    }

    public static double[] turnedPoint(float x, float y, float z, int turns) {
        double bx = x / PX, by = y / PX, bz = z / PX;
        for (int t = 0; t < Math.floorMod(turns, 4); t++) {
            double nx = 1.0 - bz;
            bz = bx;
            bx = nx;
        }
        return new double[] { bx, by, bz };
    }
}
