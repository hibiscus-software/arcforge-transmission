/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Factory distribution's models, on the towers' sheet with TowerMesh's materials and helpers (the same rules: every face
// at 16 texels a block, never stretched, each plate shading itself, ramps across round parts):
//
//  - the Cable Termination (LV, MV: a compact stress-cone pothead; HV: three porcelain-skirted terminations, a block and a
//    half; EHV: three tall composite terminations with corona rings, nearly three blocks), on top of a block, on a wall
//    bracket off its side, or hanging under it;
//  - the switchgear lineups' sections: LV and MV metal-enclosed switchgear (1 wide, 2 tall: ANSI-61 grey on a dark plinth,
//    doors, breakers), HV and EHV gas-insulated bays (2 wide, 3 tall: aluminium tanks, flanged joints, SF6 gauges, the
//    mechanism cabinet with its handle and flag, the bus tube along the top that meets the next bay's);
//  - each breaker's or switch's operator (its handle and position flag), drawn apart so the block can show it closed,
//    open or tripped.
//
// The frame is TowerMesh's: px, y up, the front north (-z); a lineup runs east (+x), so a section's west and east faces
// meet its neighbours'.
public final class FactoryMesh {
    private FactoryMesh() {}

    // ================================================================================================================
    // Cable Termination
    // ================================================================================================================

    // Where it's mounted: on top of a block (standing up), on a wall bracket off a block's side (standing up beside it,
    // the wall to the south in this frame), or under one (hanging).
    public enum Mount {
        TOP, SIDE, BOTTOM
    }

    // How tall each tier's termination stands (blocks): LV and MV within their block, HV two, EHV three.
    public static int terminationHeight(int tier) {
        return tier == 2 ? 2 : tier == 3 ? 3 : 1;
    }

    // The three conductors' x (A B C west to east) and z, by tier.
    public static float[] terminationX(int tier) {
        return switch (tier) {
            case 0 -> new float[] { 5.2F, 8.0F, 10.8F };
            case 1 -> new float[] { 4.6F, 8.0F, 11.4F };
            default -> new float[] { 2.7F, 8.0F, 13.3F };
        };
    }

    // Each conductor's terminal (its top, px in the up frame: on top of a block).
    public static float terminationTop(int tier) {
        return switch (tier) {
            case 0 -> 11.0F;
            case 1 -> 14.2F;
            case 2 -> 30.4F;
            default -> 46.6F;
        };
    }

    private static final Map<String, TowerMesh.Built> TERMINATIONS = Collections.synchronizedMap(new HashMap<>());

    public static TowerMesh.Built termination(int tier, Mount mount) {
        return TERMINATIONS.computeIfAbsent(tier + ":" + mount, k -> {
            TowerMesh m = new TowerMesh();
            terminationBody(m, tier, mount == Mount.SIDE);
            TowerMesh.Built built = built(m);
            return mount == Mount.BOTTOM ? flipped(built, 16.0F * terminationHeight(tier)) : built;
        });
    }

    private static void terminationBody(TowerMesh m, int tier, boolean bracket) {
        float c = 8;
        float[] xs = terminationX(tier);
        if (bracket) {
            // A galvanized wall bracket: a back plate on the wall (south) and a shelf out under the termination.
            m.plateBox(1, 0, 14.8F, 15, tier <= 1 ? 8 : 10, 16, TowerMesh.Mat.GALV);
            m.plateBox(1, 0, 3.0F, 15, 1.2F, 14.8F, TowerMesh.Mat.GALV);
            m.member(new float[] { 2, 1.2F, 13.8F }, new float[] { 2, 6.0F, 14.8F }, 1.0F, 1.0F, new float[] { 1, 0, 0 }, TowerMesh.Mat.GALV, true);
            m.member(new float[] { 14, 1.2F, 13.8F }, new float[] { 14, 6.0F, 14.8F }, 1.0F, 1.0F, new float[] { 1, 0, 0 }, TowerMesh.Mat.GALV, true);
            m.frame.add(new float[] { 1, 0, 3.0F, 15, tier <= 1 ? 8 : 10, 16 });
        }
        float y0 = bracket ? 1.2F : 0.0F;
        switch (tier) {
            case 0, 1 -> {
                // A cast pothead: the gland and neck, the pot (8 facets, the lid flanged), and three porcelain outlets.
                boolean mv = tier == 1;
                float r = mv ? 4.8F : 3.8F, pot0 = y0 + 2.6F, pot1 = pot0 + (mv ? 5.2F : 4.4F);
                if (!bracket) {
                    m.plateBox(3, 0, 4, 13, 0.8F, 12, TowerMesh.Mat.GALV);
                    y0 = 0.8F;
                    pot0 = y0 + 1.8F;
                    pot1 = pot0 + (mv ? 5.2F : 4.4F);
                }
                m.turned(c, c, new float[][] { { y0, 1.4F }, { y0 + 1.0F, 1.4F }, { y0 + 1.0F, 1.0F }, { pot0 - 0.4F, 1.0F } }, 6, TowerMesh.Ramp.GALV, false,
                        false);
                m.turned(c, c, new float[][] { { pot0 - 0.4F, 1.0F }, { pot0, r - 1.2F }, { pot0 + 0.8F, r }, { pot1 - 0.8F, r }, { pot1 - 0.8F, r + 0.4F },
                        { pot1, r + 0.4F } }, 8, TowerMesh.Ramp.CAN, true, false);
                for (int i = 0; i < 3; i++) {
                    float x = xs[i];
                    if (mv) {
                        m.turned(x, c, new float[][] { { pot1, 0.9F }, { pot1 + 1.0F, 0.9F }, { pot1 + 1.0F, 1.5F }, { pot1 + 1.6F, 1.5F },
                                { pot1 + 1.6F, 0.9F }, { pot1 + 2.6F, 0.9F }, { pot1 + 2.6F, 1.5F }, { pot1 + 3.2F, 1.5F }, { pot1 + 3.2F, 0.9F },
                                { pot1 + 4.2F, 0.9F }, { pot1 + 4.2F, 1.3F }, { pot1 + 4.8F, 1.3F } }, 6, TowerMesh.Ramp.PORCELAIN, true, false);
                    } else {
                        m.turned(x, c, new float[][] { { pot1, 0.9F }, { pot1 + 1.0F, 0.9F }, { pot1 + 1.0F, 1.3F }, { pot1 + 1.6F, 1.3F },
                                { pot1 + 1.6F, 0.9F }, { pot1 + 2.4F, 0.9F } }, 6, TowerMesh.Ramp.PORCELAIN, true, false);
                    }
                    float top = pot1 + (mv ? 4.8F : 2.4F);
                    // The terminal: a tinned lug on a stud.
                    m.swatchBox(x - 0.4F, top, c - 0.4F, x + 0.4F, top + 0.8F, c + 0.4F, TowerMesh.GALV0 + 6);
                    m.swatchBox(x - 0.6F, top + 0.8F, c - 0.9F, x + 0.6F, top + 1.4F, c + 0.9F, TowerMesh.GALV0 + 8);
                    m.insulators.add(new float[] { x - 1.6F, pot1, c - 1.6F, x + 1.6F, top + 1.4F, c + 1.6F });
                }
                m.frame.add(new float[] { c - r - 0.4F, y0, c - r - 0.4F, c + r + 0.4F, pot1, c + r + 0.4F });
            }
            case 2 -> {
                // Three outdoor porcelain terminations on a galvanized beam: each a support foot, the aluminium stress-cone
                // housing, the brown porcelain with big and small sheds in turn, and the top fitting with its terminal.
                if (!bracket) {
                    m.plateBox(0.5F, 0, 6.5F, 15.5F, 1.2F, 9.5F, TowerMesh.Mat.GALV);
                    y0 = 1.2F;
                }
                for (int i = 0; i < 3; i++) {
                    float x = xs[i], y = y0;
                    m.turned(x, c, new float[][] { { y, 1.8F }, { y + 0.8F, 1.8F }, { y + 0.8F, 1.0F }, { y + 1.8F, 1.0F } }, 8, TowerMesh.Ramp.GALV, true,
                            false);
                    y += 1.8F;
                    m.turned(x, c, new float[][] { { y, 1.9F }, { y + 4.2F, 1.9F }, { y + 4.2F, 1.4F } }, 8, TowerMesh.Ramp.ALUM, true, true);
                    y += 4.2F;
                    List<float[]> profile = new ArrayList<>();
                    profile.add(new float[] { y, 1.2F });
                    boolean big = true;
                    for (float s = y + 1.0F; s < 27.2F; s += 1.6F) {
                        float shed = big ? 2.4F : 1.9F;
                        profile.add(new float[] { s, 1.2F });
                        profile.add(new float[] { s, shed });
                        profile.add(new float[] { s + 0.5F, shed });
                        profile.add(new float[] { s + 0.9F, 1.2F });
                        big = !big;
                    }
                    profile.add(new float[] { 28.0F, 1.2F });
                    m.turned(x, c, profile.toArray(new float[0][]), 8, TowerMesh.Ramp.PORCELAIN, false, false);
                    m.turned(x, c, new float[][] { { 28.0F, 1.6F }, { 29.2F, 1.6F }, { 29.2F, 0.6F }, { 30.4F, 0.6F } }, 8, TowerMesh.Ramp.ALUM, true, true);
                    m.swatchBox(x - 0.3F, 29.4F, c - 1.2F, x + 0.3F, 30.4F, c + 1.2F, TowerMesh.GALV0 + 8);
                    m.insulators.add(new float[] { x - 2.4F, y0, c - 2.4F, x + 2.4F, 30.4F, c + 2.4F });
                }
            }
            default -> {
                // Three tall composite terminations: the aluminium stress-cone housing, the grey silicone core with sheds
                // alternating big and small, the top fitting, and a corona ring on a stud above it.
                if (!bracket) {
                    m.plateBox(0.5F, 0, 6.5F, 15.5F, 1.2F, 9.5F, TowerMesh.Mat.GALV);
                    y0 = 1.2F;
                }
                for (int i = 0; i < 3; i++) {
                    float x = xs[i], y = y0;
                    m.turned(x, c, new float[][] { { y, 1.9F }, { y + 0.8F, 1.9F }, { y + 0.8F, 1.1F }, { y + 1.6F, 1.1F } }, 8, TowerMesh.Ramp.GALV, true,
                            false);
                    y += 1.6F;
                    m.turned(x, c, new float[][] { { y, 2.1F }, { y + 5.0F, 2.1F }, { y + 5.0F, 1.5F }, { y + 5.8F, 1.5F } }, 8, TowerMesh.Ramp.ALUM, true,
                            true);
                    y += 5.8F;
                    List<float[]> profile = new ArrayList<>();
                    profile.add(new float[] { y, 1.0F });
                    boolean big = true;
                    for (float s = y + 0.8F; s < 40.6F; s += 1.4F) {
                        float shed = big ? 2.3F : 1.7F;
                        profile.add(new float[] { s, 1.0F });
                        profile.add(new float[] { s, shed });
                        profile.add(new float[] { s + 0.4F, shed });
                        profile.add(new float[] { s + 0.7F, 1.0F });
                        big = !big;
                    }
                    profile.add(new float[] { 41.2F, 1.0F });
                    m.turned(x, c, profile.toArray(new float[0][]), 8, TowerMesh.Ramp.SILICONE, false, false);
                    m.turned(x, c, new float[][] { { 41.2F, 1.4F }, { 42.4F, 1.4F }, { 42.4F, 0.5F }, { 45.0F, 0.5F } }, 8, TowerMesh.Ramp.ALUM, true, false);
                    // The corona ring: eight aluminium bars round the stud, on four spokes.
                    float ry = 44.2F, rr = 2.2F;
                    float[][] ring = TowerMesh.ring(x, c, rr, 8);
                    for (int k = 0; k < 8; k++) {
                        float[] a = { ring[k][0], ry, ring[k][2] }, b = { ring[(k + 1) % 8][0], ry, ring[(k + 1) % 8][2] };
                        m.member(a, b, 0.6F, 0.6F, TowerMesh.UP, TowerMesh.Mat.ALUM, false);
                    }
                    for (int k = 0; k < 8; k += 2) {
                        float[] mid = { (ring[k][0] + ring[(k + 1) % 8][0]) / 2, ry, (ring[k][2] + ring[(k + 1) % 8][2]) / 2 };
                        m.rod(new float[] { x, ry, c }, mid, 0.3F, TowerMesh.ALUM0 + 3);
                    }
                    m.turned(x, c, new float[][] { { 45.0F, 0.9F }, { 46.6F, 0.9F } }, 8, TowerMesh.Ramp.ALUM, true, false);
                    m.insulators.add(new float[] { x - 2.5F, y0, c - 2.5F, x + 2.5F, 46.6F, c + 2.5F });
                }
            }
        }
    }

    // ================================================================================================================
    // Switchgear lineups
    // ================================================================================================================

    // A lineup's sections: where its circuit comes in (a load-break switch and fuses at LV and MV; a breaker with its
    // disconnector and earthing switch at HV and EHV), the step-down transformer (MV, HV, EHV), the main breaker, the
    // feeders (two breakers a section at LV and MV, one a bay at HV and EHV) and the bus tie.
    public enum Section {
        INCOMING, TRANSFORMER, MAIN, FEEDER, TIE
    }

    // What a section's operator works: a breaker (its handle and flag), the incomer's load-break switch (a rotary
    // handle), a GIS disconnector or earthing switch (a small flag and handle).
    public enum Op {
        BREAKER, SWITCH, DISCONNECTOR, EARTH
    }

    // Closed (flag red), open (green) or tripped (yellow: a breaker tripped, a fuse blown); a tripped breaker's handle
    // stands in the middle.
    public enum OpState {
        CLOSED, OPEN, TRIPPED
    }

    // An operator's place on a section's front: its kind, and the point on the face it stands out from (px; it stands
    // out north, -z).
    public record Operator(Op op, float x, float y, float z) {}

    // How tall a section's body is (blocks): LV and MV 2, HV and EHV 3 (an incomer's bushings stand above).
    public static int sectionHeight(int tier) {
        return tier <= 1 ? 2 : 3;
    }

    // How wide (blocks, east along the lineup): LV and MV 1, HV and EHV 2.
    public static int sectionWidth(int tier) {
        return tier <= 1 ? 1 : 2;
    }

    private static final Map<String, TowerMesh.Built> SECTIONS = Collections.synchronizedMap(new HashMap<>());

    public static TowerMesh.Built section(Section section, int tier) {
        return SECTIONS.computeIfAbsent(section + ":" + tier, k -> {
            TowerMesh m = new TowerMesh();
            if (tier <= 1) {
                metalEnclosed(m, section, tier);
            } else {
                gisBay(m, section, tier);
            }
            return built(m);
        });
    }

    public static TowerMesh.Built operator(Op op, int tier, OpState state) {
        return SECTIONS.computeIfAbsent("op:" + op + ":" + tier + ":" + state, k -> {
            TowerMesh m = new TowerMesh();
            operatorBody(m, op, tier, state);
            return built(m);
        });
    }

    // Where a section's operators are, west to east, bottom to top (the order the block numbers its breakers in).
    public static List<Operator> operators(Section section, int tier) {
        if (tier <= 1) {
            return switch (section) {
                case INCOMING -> List.of(new Operator(Op.SWITCH, 8.0F, 10.0F, 0.5F));
                case TRANSFORMER -> List.of();
                case MAIN, TIE -> List.of(new Operator(Op.BREAKER, 8.0F, 9.0F, 0.2F));
                case FEEDER -> tier == 0 ? List.of(new Operator(Op.BREAKER, 5.5F, 9.0F, 0.2F), new Operator(Op.BREAKER, 5.5F, 22.6F, 0.2F))
                        : List.of(new Operator(Op.BREAKER, 6.0F, 8.5F, 0.2F), new Operator(Op.BREAKER, 6.0F, 22.3F, 0.2F));
            };
        }
        return switch (section) {
            case INCOMING -> List.of(new Operator(Op.BREAKER, 10.0F, 6.5F, 0.0F), new Operator(Op.DISCONNECTOR, 19.0F, 8.0F, 0.2F),
                    new Operator(Op.EARTH, 24.0F, 8.0F, 0.2F));
            case TRANSFORMER -> List.of();
            default -> List.of(new Operator(Op.BREAKER, 10.0F, 6.5F, 0.0F));
        };
    }

    // --- LV and MV: metal-enclosed switchgear ---

    // A cubicle 1 wide and 2 tall: the dark plinth, the ANSI-61 grey body and its roof, and the section's doors and gear
    // on its front (the operators apart). MV stands a touch more solid: full-width doors with inspection windows, relays
    // on every door, pressure-relief flaps on its roof; LV has the cable compartment's narrow door down its east side.
    private static void metalEnclosed(TowerMesh m, Section section, int tier) {
        boolean mv = tier == 1;
        float roof = mv ? 31.4F : 32.0F;
        m.plateBox(0, 0, 1.4F, 16, 2, 16, TowerMesh.Mat.PLINTH);
        if (section == Section.TRANSFORMER) {
            // The dry-type transformer's cubicle: a guarded niche in the front where its three cast-resin coils show.
            m.plateBox(0, 2, 4, 16, 30.6F, 16, TowerMesh.Mat.PAINT);
            m.plateBox(0, 2, 1, 2.5F, 30.6F, 4, TowerMesh.Mat.PAINT);
            m.plateBox(13.5F, 2, 1, 16, 30.6F, 4, TowerMesh.Mat.PAINT);
            m.plateBox(2.5F, 24, 1, 13.5F, 30.6F, 4, TowerMesh.Mat.PAINT);
            m.plateBox(2.5F, 2, 1, 13.5F, 7, 4, TowerMesh.Mat.PAINT);
        } else {
            m.plateBox(0, 2, 1, 16, 30.6F, 16, TowerMesh.Mat.PAINT);
        }
        m.plateBox(0, 30.6F, 0.4F, 16, roof, 16, TowerMesh.Mat.PAINT);
        m.frame.add(new float[] { 0, 0, 0, 16, 32, 16 });
        if (mv) {
            for (float x : new float[] { 1.5F, 8.5F }) {
                m.plateBox(x, roof, 3, x + 6, 32, 13, TowerMesh.Mat.PAINT);
            }
        }
        switch (section) {
            case INCOMING -> {
                // Upper door: the fuses behind their window; lower: the load-break switch's handle. The overhead line's
                // bushings stand on the roof.
                door(m, 0.5F, 17, 15.5F, 30);
                window(m, 3, 19.5F, 13, 28.5F);
                for (float x : new float[] { 5, 8, 11 }) {
                    m.swatchBox(x - 0.7F, mv ? 20.6F : 21.4F, 0.2F, x + 0.7F, mv ? 27.4F : 26.6F, 0.35F, TowerMesh.WHITE);
                    for (float y : mv ? new float[] { 20.0F, 27.4F } : new float[] { 20.8F, 26.6F }) {
                        m.swatchBox(x - 0.9F, y, 0.15F, x + 0.9F, y + 0.6F, 0.35F, TowerMesh.GALV0 + 7);
                    }
                }
                door(m, 0.5F, 3, 15.5F, 16);
                escutcheon(m, 5.0F, 6.6F, 11.0F, 13.4F);
                nameplate(m, 6, 4.4F);
                for (int i = 0; i < 3; i++) {
                    roofBushing(m, tier, 4 + 4 * i, mv ? 32 : roof, 8);
                }
            }
            case TRANSFORMER -> {
                // The coils on their core in the niche, a guard of bars across it, the fans under it and the temperature
                // monitor above.
                m.swatchBox(3.0F, 7.6F, 2.2F, 13.0F, 9.0F, 4.0F, TowerMesh.RUBBER0 + 2);
                m.swatchBox(3.0F, 22.0F, 2.2F, 13.0F, 23.4F, 4.0F, TowerMesh.RUBBER0 + 2);
                for (float x : new float[] { 4.7F, 8.0F, 11.3F }) {
                    m.turned(x, 3.4F, new float[][] { { 9.0F, 1.5F }, { 22.0F, 1.5F } }, 8, TowerMesh.Ramp.SILICONE, false, false);
                }
                for (float x = 3.4F; x < 13.0F; x += 1.6F) {
                    m.swatchBox(x, 7.0F, 1.1F, x + 0.4F, 24.0F, 1.4F, TowerMesh.GALV0 + 6);
                }
                for (float x : new float[] { 4.5F, 11.5F }) {
                    fan(m, x, 4.5F, 1.0F);
                }
                louvres(m, 3.5F, 26.0F, 9.0F, 29.6F, 1.0F);
                m.swatchBox(10.0F, 26.0F, 0.6F, 12.8F, 29.0F, 1.0F, TowerMesh.RUBBER0 + 1);
                m.swatchBox(10.4F, 27.6F, 0.5F, 12.4F, 28.6F, 0.6F, TowerMesh.AMBER);
                m.swatchBox(10.4F, 26.5F, 0.5F, 10.9F, 27.0F, 0.6F, TowerMesh.GREEN);
            }
            case MAIN, TIE -> {
                // Upper door: meters and the trip unit (a tie shows its mimic: the bus either side of it); lower door:
                // the breaker's escutcheon.
                door(m, 0.5F, 17, 15.5F, 30);
                if (section == Section.MAIN) {
                    meter(m, 4.5F, 25.0F);
                    meter(m, 9.0F, 25.0F);
                } else {
                    m.swatchBox(2.0F, 24.6F, 0.35F, 6.4F, 25.4F, 0.5F, TowerMesh.BLACK);
                    m.swatchBox(9.6F, 24.6F, 0.35F, 14.0F, 25.4F, 0.5F, TowerMesh.BLACK);
                    m.swatchBox(6.4F, 23.4F, 0.35F, 9.6F, 26.6F, 0.5F, TowerMesh.BLACK);
                    m.swatchBox(6.9F, 23.9F, 0.3F, 9.1F, 26.1F, 0.35F, TowerMesh.PAINT0 + 4);
                }
                relay(m, 3.0F, 18.6F, 9.0F, 21.6F);
                door(m, 0.5F, 3, 15.5F, 16);
                escutcheon(m, 4.6F, 4.8F, 11.4F, 14.0F);
                slot(m, 8.0F, 9.0F);
                nameplate(m, 12.4F, 14.4F);
                if (mv) {
                    window(m, 12.0F, 4.2F, 13.4F, 7.4F);
                }
            }
            case FEEDER -> {
                if (mv) {
                    // Two-high: a breaker behind each door, its relay beside its handle, a small inspection window.
                    for (float y0 : new float[] { 3, 16.8F }) {
                        door(m, 0.5F, y0, 15.5F, y0 + 13.2F);
                        escutcheon(m, 3.0F, y0 + 1.6F, 9.0F, y0 + 9.6F);
                        slot(m, 6.0F, y0 + 5.5F);
                        relay(m, 10.0F, y0 + 6.4F, 13.4F, y0 + 11.6F);
                        window(m, 10.4F, y0 + 1.6F, 13.0F, y0 + 4.4F);
                        nameplate(m, 3.0F, y0 + 10.6F);
                    }
                } else {
                    // Two breakers on the west, the cable compartment's narrow door down the east.
                    for (float y0 : new float[] { 3, 16.6F }) {
                        door(m, 0.5F, y0, 10.6F, y0 + 13.0F);
                        escutcheon(m, 2.6F, y0 + 1.8F, 8.4F, y0 + 10.0F);
                        slot(m, 5.5F, y0 + 6.0F);
                        nameplate(m, 6.6F, y0 + 11.0F);
                    }
                    door(m, 11.0F, 3, 15.5F, 29.6F);
                    louvres(m, 11.8F, 4.4F, 14.2F, 9.0F, 0.5F);
                    louvres(m, 11.8F, 23.4F, 14.2F, 28.0F, 0.5F);
                }
            }
        }
    }

    // A door on the front from (x0, y0) to (x1, y1): a plate standing proud of the body, two hinges on its west edge and
    // a bar handle on its east.
    private static void door(TowerMesh m, float x0, float y0, float x1, float y1) {
        m.plateBox(x0, y0, 0.5F, x1, y1, 1.0F, TowerMesh.Mat.PAINT);
        for (float y : new float[] { y0 + 1.2F, y1 - 2.6F }) {
            m.swatchBox(x0 - 0.2F, y, 0.3F, x0 + 0.5F, y + 1.4F, 0.9F, TowerMesh.PAINT0 + 2);
        }
        float ym = (y0 + y1) / 2;
        m.swatchBox(x1 - 1.4F, ym - 1.6F, 0.0F, x1 - 0.8F, ym + 1.6F, 0.2F, TowerMesh.RUBBER0 + 3);
        m.swatchBox(x1 - 1.4F, ym - 1.4F, 0.2F, x1 - 0.8F, ym - 0.8F, 0.5F, TowerMesh.RUBBER0 + 2);
        m.swatchBox(x1 - 1.4F, ym + 0.8F, 0.2F, x1 - 0.8F, ym + 1.4F, 0.5F, TowerMesh.RUBBER0 + 2);
    }

    // A breaker's escutcheon: the dark grey plate its front shows through the door.
    private static void escutcheon(TowerMesh m, float x0, float y0, float x1, float y1) {
        m.swatchBox(x0, y0, 0.3F, x1, y1, 0.5F, TowerMesh.PAINT0 + 1);
    }

    // The slot a breaker's handle moves in, centred at (x, y).
    private static void slot(TowerMesh m, float x, float y) {
        m.swatchBox(x - 0.7F, y - 2.4F, 0.2F, x + 0.7F, y + 2.4F, 0.3F, TowerMesh.RUBBER0);
    }

    // A dark window in a door, framed.
    private static void window(TowerMesh m, float x0, float y0, float x1, float y1) {
        m.swatchBox(x0 - 0.4F, y0 - 0.4F, 0.4F, x1 + 0.4F, y1 + 0.4F, 0.5F, TowerMesh.PAINT0 + 2);
        m.swatchBox(x0, y0, 0.35F, x1, y1, 0.4F, TowerMesh.RUBBER0);
        // A glint in its top left corner.
        m.swatchBox(x0 + 0.4F, y1 - 1.2F, 0.3F, x0 + 0.8F, y1 - 0.4F, 0.35F, TowerMesh.SIL0 + 4);
    }

    // An analog panel meter: a black bezel, the white dial, the needle.
    private static void meter(TowerMesh m, float x, float y) {
        m.swatchBox(x - 1.8F, y - 1.8F, 0.3F, x + 1.8F, y + 1.8F, 0.5F, TowerMesh.RUBBER0 + 1);
        m.swatchBox(x - 1.3F, y - 1.3F, 0.25F, x + 1.3F, y + 1.3F, 0.3F, TowerMesh.WHITE);
        m.swatchBox(x - 0.15F, y - 1.0F, 0.2F, x + 0.15F, y + 0.7F, 0.25F, TowerMesh.BLACK);
    }

    // A protection relay or trip unit: a black case, its display and two LEDs.
    private static void relay(TowerMesh m, float x0, float y0, float x1, float y1) {
        m.swatchBox(x0, y0, 0.25F, x1, y1, 0.5F, TowerMesh.RUBBER0 + 1);
        m.swatchBox(x0 + 0.5F, y1 - 1.6F, 0.2F, x1 - 0.5F, y1 - 0.5F, 0.25F, TowerMesh.MARKER);
        m.swatchBox(x0 + 0.5F, y0 + 0.5F, 0.15F, x0 + 1.0F, y0 + 1.0F, 0.25F, TowerMesh.RED);
        m.swatchBox(x0 + 1.4F, y0 + 0.5F, 0.15F, x0 + 1.9F, y0 + 1.0F, 0.25F, TowerMesh.GREEN);
    }

    // A white nameplate with its black lettering, 3 x 1 px from its bottom left at (x, y).
    private static void nameplate(TowerMesh m, float x, float y) {
        m.swatchBox(x, y, 0.4F, x + 3.0F, y + 1.0F, 0.5F, TowerMesh.WHITE);
        m.swatchBox(x + 0.5F, y + 0.4F, 0.35F, x + 2.5F, y + 0.6F, 0.4F, TowerMesh.BLACK);
    }

    // Louvres: slats with a shadow under each, in front of the face at z.
    private static void louvres(TowerMesh m, float x0, float y0, float x1, float y1, float z) {
        m.swatchBox(x0, y0, z - 0.05F, x1, y1, z, TowerMesh.RUBBER0 + 1);
        for (float y = y0 + 0.4F; y + 0.8F <= y1; y += 1.4F) {
            m.swatchBox(x0 + 0.2F, y + 0.4F, z - 0.35F, x1 - 0.2F, y + 0.8F, z - 0.05F, TowerMesh.PAINT0 + 5);
            m.swatchBox(x0 + 0.2F, y, z - 0.25F, x1 - 0.2F, y + 0.4F, z - 0.05F, TowerMesh.PAINT0 + 1);
        }
    }

    // A cooling fan's guard on a face at z: a dark opening, a cross of bars and a ring round it, the hub.
    private static void fan(TowerMesh m, float x, float y, float z) {
        m.swatchBox(x - 2.0F, y - 2.0F, z - 0.1F, x + 2.0F, y + 2.0F, z, TowerMesh.RUBBER0);
        m.swatchBox(x - 1.6F, y - 0.2F, z - 0.4F, x + 1.6F, y + 0.2F, z - 0.1F, TowerMesh.GALV0 + 6);
        m.swatchBox(x - 0.2F, y - 1.6F, z - 0.4F, x + 0.2F, y + 1.6F, z - 0.1F, TowerMesh.GALV0 + 6);
        m.swatchBox(x - 0.7F, y - 0.7F, z - 0.6F, x + 0.7F, y + 0.7F, z - 0.4F, TowerMesh.GALV0 + 4);
        for (float[] r : new float[][] { { -2.0F, -2.0F, 2.0F, -1.6F }, { -2.0F, 1.6F, 2.0F, 2.0F }, { -2.0F, -1.6F, -1.6F, 1.6F },
                { 1.6F, -1.6F, 2.0F, 1.6F } }) {
            m.swatchBox(x + r[0], y + r[1], z - 0.4F, x + r[2], y + r[3], z - 0.1F, TowerMesh.GALV0 + 7);
        }
    }

    // A bushing on a roof at (x, y, z) taking an overhead line of its tier: LV the pole-mount transformer's primary, MV
    // its MV primary's three sheds, each on a steel flange with its terminal stud on top.
    private static void roofBushing(TowerMesh m, int tier, float x, float y, float z) {
        m.turned(x, z, new float[][] { { y, 1.8F }, { y + 0.6F, 1.8F } }, 8, TowerMesh.Ramp.GALV, true, false);
        float b = y + 0.6F, top;
        if (tier == 0) {
            m.turned(x, z, new float[][] { { b, 0.9F }, { b + 1.2F, 0.9F }, { b + 1.2F, 1.3F }, { b + 1.8F, 1.3F }, { b + 1.8F, 0.9F }, { b + 2.6F, 0.9F },
                    { b + 2.6F, 1.2F }, { b + 3.2F, 1.2F } }, 6, TowerMesh.Ramp.PORCELAIN, true, false);
            top = b + 3.2F;
        } else {
            m.turned(x, z, new float[][] { { b, 0.9F }, { b + 1.0F, 0.9F }, { b + 1.0F, 1.6F }, { b + 1.6F, 1.6F }, { b + 1.6F, 0.9F }, { b + 2.6F, 0.9F },
                    { b + 2.6F, 1.6F }, { b + 3.2F, 1.6F }, { b + 3.2F, 0.9F }, { b + 4.2F, 0.9F }, { b + 4.2F, 1.6F }, { b + 4.8F, 1.6F }, { b + 4.8F, 0.9F },
                    { b + 5.6F, 0.9F } }, 6, TowerMesh.Ramp.PORCELAIN, true, false);
            top = b + 5.6F;
        }
        m.swatchBox(x - 0.4F, top, z - 0.4F, x + 0.4F, top + 0.8F, z + 0.4F, TowerMesh.GALV0 + 7);
        m.insulators.add(new float[] { x - 1.8F, y, z - 1.8F, x + 1.8F, top + 0.8F, z + 1.8F });
    }

    // The top of a roof bushing (its wire's attach point), px above the roof.
    public static float roofBushingTop(int tier) {
        return tier == 0 ? 0.6F + 3.2F + 0.8F : 0.6F + 5.6F + 0.8F;
    }

    // --- HV and EHV: gas-insulated bays ---

    // A bay 2 wide and 3 tall: the galvanized base frame; the mechanism cabinet across the front at the bottom; the
    // breaker (HV one three-phase tank, EHV a tank a phase) with flanged joints and SF6 density gauges; above it the
    // disconnector and earthing switch module; and the bus tube along the top, its flange meeting the next bay's.
    private static void gisBay(TowerMesh m, Section section, int tier) {
        boolean ehv = tier == 3;
        float cz = 10;
        m.plateBox(0.5F, 0, 1.0F, 31.5F, 1.6F, 3.0F, TowerMesh.Mat.GALV);
        m.plateBox(0.5F, 0, 13.0F, 31.5F, 1.6F, 15.0F, TowerMesh.Mat.GALV);
        for (float x : new float[] { 1.5F, 30.5F }) {
            m.plateBox(x - 1.0F, 0, 3.0F, x + 1.0F, 1.6F, 13.0F, TowerMesh.Mat.GALV);
        }
        m.frame.add(new float[] { 0, 0, 0, 32, 48, 16 });
        if (section == Section.TRANSFORMER) {
            oilTransformer(m);
            bus(m, tier, false, true);
            return;
        }
        // The mechanism cabinet: its door, local controls and nameplate (the operators apart).
        m.plateBox(3, 1.6F, 0.5F, 29, 12.4F, 3.6F, TowerMesh.Mat.PAINT);
        m.plateBox(3.6F, 2.2F, 0.2F, 28.4F, 11.8F, 0.5F, TowerMesh.Mat.PAINT);
        m.swatchBox(6.0F, 3.4F, 0.0F, 14.0F, 10.6F, 0.2F, TowerMesh.RUBBER0 + 1);
        m.swatchBox(15.4F, 3.6F, -0.1F, 16.4F, 4.6F, 0.2F, TowerMesh.GREEN);
        m.swatchBox(17.0F, 3.6F, -0.1F, 18.0F, 4.6F, 0.2F, TowerMesh.RED);
        nameplate(m, 22.0F, 3.6F);
        // The breaker: where a feeder's cable comes up into it, its tank stands on the cable sealing end.
        float t0 = section == Section.FEEDER ? 9.0F : 1.6F, t1 = 30.0F;
        float[] xs = ehv ? new float[] { 7, 16, 25 } : new float[] { 16 };
        float r = ehv ? 3.8F : 6.0F, fl = r + 0.7F;
        for (float x : xs) {
            m.turned(x, cz, new float[][] { { t0, r - 0.6F }, { t0 + 0.6F, r }, { 12.6F, r }, { 12.6F, fl }, { 13.6F, fl }, { 13.6F, r }, { 28.4F, r },
                    { 28.4F, fl }, { 29.4F, fl }, { 29.4F, r - 0.4F }, { t1, r - 0.4F } }, ehv ? 10 : 14, TowerMesh.Ramp.ALUM, true, true);
            bolts(m, x, cz, 13.6F, fl, ehv ? 6 : 10);
            bolts(m, x, cz, 29.4F, fl, ehv ? 6 : 10);
            gauge(m, x + (ehv ? 0 : 3.0F), 22.0F, cz - r);
            if (section == Section.FEEDER) {
                // The cable sealing end under the tank, down to its gland in the floor.
                m.turned(x, cz + 1.0F, new float[][] { { 1.6F, 1.6F }, { 2.4F, 1.6F }, { 2.4F, ehv ? 2.2F : 3.2F }, { t0, ehv ? 2.2F : 3.2F } }, 10,
                        TowerMesh.Ramp.ALUM, false, false);
            }
            if (section == Section.MAIN) {
                // The current transformers' housing: a wider band round the tank.
                m.turned(x, cz, new float[][] { { 17.0F, r }, { 17.0F, r + 0.5F }, { 20.6F, r + 0.5F }, { 20.6F, r } }, ehv ? 10 : 14, TowerMesh.Ramp.PAINT,
                        false, false);
            }
        }
        // The disconnector and earthing switch: HV one module along the bay over the tank, EHV one over each tank.
        if (ehv) {
            for (float x : xs) {
                m.turned(x, cz, new float[][] { { 30.0F, 1.6F }, { 31.0F, 1.6F }, { 31.0F, 2.8F }, { 36.2F, 2.8F } }, 10, TowerMesh.Ramp.ALUM, true, false);
                m.plateBox(x + 2.4F, 32.0F, cz - 1.2F, x + 3.8F, 35.0F, cz + 1.2F, TowerMesh.Mat.PAINT);
            }
        } else {
            m.turned(16, cz, new float[][] { { 30.0F, 2.2F }, { 33.0F, 2.2F } }, 10, TowerMesh.Ramp.ALUM, false, false);
            along(m, new float[] { 6.0F, 36.0F, cz }, 20.0F, new float[][] { { 0, 3.0F }, { 0, 3.6F }, { 0.8F, 3.6F }, { 0.8F, 3.0F }, { 19.2F, 3.0F },
                    { 19.2F, 3.6F }, { 20.0F, 3.6F } }, 12, TowerMesh.Ramp.ALUM, true, true);
            m.plateBox(26.0F, 33.4F, cz - 2.0F, 28.6F, 38.6F, cz + 2.0F, TowerMesh.Mat.PAINT);
            gauge(m, 10.0F, 36.0F, cz - 3.0F);
        }
        bus(m, tier, section == Section.TIE, false);
        if (section == Section.INCOMING) {
            airBushings(m, tier);
        }
    }

    // The bus along the top of every bay: HV one three-phase tube, EHV three single-phase tubes in trefoil; flanged at
    // both ends so a neighbouring bay's flange meets this one's. Tied (a bus tie): split at the middle by a partition.
    // Over a transformer it drops to the transformer's high side instead of to a module.
    private static void bus(TowerMesh m, int tier, boolean tie, boolean transformer) {
        boolean ehv = tier == 3;
        float cz = 10;
        float[][] tubes = ehv ? new float[][] { { 41.0F, 6.6F }, { 41.0F, 13.4F }, { 45.6F, 10.0F } } : new float[][] { { 42.6F, cz } };
        float r = ehv ? 2.0F : 3.2F, fl = r + 0.6F;
        for (int k = 0; k < tubes.length; k++) {
            float y = tubes[k][0], z = tubes[k][1];
            along(m, new float[] { 0, y, z }, 32.0F, new float[][] { { 0, 0 }, { 0, fl }, { 0.8F, fl }, { 0.8F, r }, { 31.2F, r }, { 31.2F, fl }, { 32.0F, fl },
                    { 32.0F, 0 } }, ehv ? 10 : 12, TowerMesh.Ramp.ALUM, false, false);
            if (tie) {
                along(m, new float[] { 15.2F, y, z }, 1.6F, new float[][] { { 0, r }, { 0, fl + 0.4F }, { 1.6F, fl + 0.4F }, { 1.6F, r } }, ehv ? 10 : 12,
                        TowerMesh.Ramp.PAINT, false, false);
            }
            if (transformer) {
                continue;
            }
            // The drop from its module (EHV: phase k's, over tank k).
            float x = ehv ? new float[] { 7, 25, 16 }[k] : 16;
            float y0 = ehv ? 36.2F : 39.6F;
            m.turned(x, z, new float[][] { { y0, ehv ? 1.2F : 1.8F }, { y - r + 0.4F, ehv ? 1.2F : 1.8F } }, 8, TowerMesh.Ramp.ALUM, false, false);
            if (ehv && Math.abs(z - cz) > 0.1F) {
                // Over to its tube from the tank's line: a short tube along z.
                float z0 = Math.min(z, cz), z1 = Math.max(z, cz);
                round(m, new float[] { x, 36.8F, z0 }, new float[] { 0, 0, 1 }, new float[] { 1, 0, 0 }, new float[][] { { 0, 1.2F }, { z1 - z0, 1.2F } }, 8,
                        TowerMesh.Ramp.ALUM, true, true);
            }
        }
        if (transformer) {
            // The transformer's high side meets the bus from below.
            m.turned(20.0F, cz, new float[][] { { 40.0F, ehv ? 1.4F : 2.2F }, { (ehv ? 45.6F : 42.6F) - r + 0.4F, ehv ? 1.4F : 2.2F } }, 8, TowerMesh.Ramp.ALUM,
                    false, false);
        }
        m.frame.add(new float[] { 0, 36.0F, cz - 5.0F, 32, 48, cz + 5.0F });
    }

    // An incoming bay's SF6-to-air bushings, one a phase standing off the bus: HV brown porcelain, EHV grey composite
    // with a corona ring on top, their tops the overhead line's attach points.
    private static void airBushings(TowerMesh m, int tier) {
        boolean ehv = tier == 3;
        float cz = 10, y = 47.0F;
        for (float x : new float[] { 6, 16, 26 }) {
            m.turned(x, cz, new float[][] { { 44.0F, 2.6F }, { 46.0F, 2.6F }, { 46.0F, 3.2F }, { y, 3.2F } }, 10, TowerMesh.Ramp.ALUM, true, false);
            float top = airBushingTop(tier);
            List<float[]> profile = new ArrayList<>();
            float core = ehv ? 1.2F : 1.4F;
            profile.add(new float[] { y, core });
            boolean big = true;
            for (float s = y + 1.0F; s < top - 3.0F; s += ehv ? 1.4F : 1.8F) {
                float shed = big ? (ehv ? 2.6F : 2.8F) : (ehv ? 2.0F : 2.2F);
                profile.add(new float[] { s, core });
                profile.add(new float[] { s, shed });
                profile.add(new float[] { s + (ehv ? 0.4F : 0.6F), shed });
                profile.add(new float[] { s + (ehv ? 0.7F : 1.0F), core });
                big = !big;
            }
            profile.add(new float[] { top - 2.4F, core });
            m.turned(x, cz, profile.toArray(new float[0][]), 8, ehv ? TowerMesh.Ramp.SILICONE : TowerMesh.Ramp.PORCELAIN, false, false);
            m.turned(x, cz, new float[][] { { top - 2.4F, 1.8F }, { top - 1.2F, 1.8F }, { top - 1.2F, 0.6F }, { top, 0.6F } }, 8, TowerMesh.Ramp.ALUM, true, false);
            if (ehv) {
                float ry = top - 1.8F;
                float[][] ring = TowerMesh.ring(x, cz, 3.6F, 10);
                for (int k = 0; k < 10; k++) {
                    m.member(new float[] { ring[k][0], ry, ring[k][2] }, new float[] { ring[(k + 1) % 10][0], ry, ring[(k + 1) % 10][2] }, 0.7F, 0.7F,
                            TowerMesh.UP, TowerMesh.Mat.ALUM, false);
                }
                for (int k = 0; k < 10; k += 2) {
                    m.rod(new float[] { x, ry, cz }, new float[] { (ring[k][0] + ring[(k + 1) % 10][0]) / 2, ry, (ring[k][2] + ring[(k + 1) % 10][2]) / 2 },
                            0.35F, TowerMesh.ALUM0 + 3);
                }
            }
            m.insulators.add(new float[] { x - 3.2F, 44.0F, cz - 3.2F, x + 3.2F, top, cz + 3.2F });
        }
    }

    // An incoming bay's air bushings' tops (px), the overhead line's attach points.
    public static float airBushingTop(int tier) {
        return tier == 3 ? 78.0F : 68.0F;
    }

    // A step-down transformer in a GIS bay's footprint: oil-filled (ONAN / ONAF), its tank between two radiator banks
    // with a fan under each, the conservator on top, the off-load tap changer's handle and the temperature gauge on its
    // front, the low-voltage cable box beside them; its high side rises to the bus by a gas-insulated tube.
    private static void oilTransformer(TowerMesh m) {
        float cz = 8;
        m.plateBox(8.0F, 1.6F, 2.5F, 24.0F, 30.0F, 14.5F, TowerMesh.Mat.PAINT);
        m.plateBox(7.4F, 30.0F, 1.9F, 24.6F, 31.4F, 15.1F, TowerMesh.Mat.PAINT);
        for (int side = -1; side <= 1; side += 2) {
            float inner = side < 0 ? 8.0F : 24.0F, outer = inner + side * 6.0F;
            float x0 = Math.min(inner, outer), x1 = Math.max(inner, outer);
            for (float z = 3.6F; z <= 13.6F; z += 2.0F) {
                m.plateBox(x0, 8.0F, z - 0.4F, x1, 27.0F, z + 0.4F, TowerMesh.Mat.PAINT);
            }
            for (float y : new float[] { 6.6F, 27.0F }) {
                m.plateBox(x0 + (side > 0 ? 0 : 1), y, 3.0F, x1 - (side > 0 ? 1 : 0), y + 1.4F, 14.2F, TowerMesh.Mat.PAINT);
            }
            // Its fan under it, blowing up into the fins.
            float fx = (x0 + x1) / 2;
            m.turned(fx, cz + 0.6F, new float[][] { { 1.6F, 2.6F }, { 5.2F, 2.6F }, { 5.2F, 2.0F } }, 10, TowerMesh.Ramp.GALV, false, false);
            m.turned(fx, cz + 0.6F, new float[][] { { 4.6F, 2.0F }, { 4.6F, 0.6F }, { 5.0F, 0.6F }, { 5.0F, 0.0F } }, 8, TowerMesh.Ramp.RUBBER, false, false);
            m.frame.add(new float[] { x0, 1.6F, 3.0F, x1, 28.4F, 14.2F });
        }
        // The conservator on its saddles.
        along(m, new float[] { 9.0F, 35.2F, 6.0F }, 14.0F, new float[][] { { 0, 3.0F }, { 14.0F, 3.0F } }, 12, TowerMesh.Ramp.PAINT, true, true);
        for (float x : new float[] { 11.0F, 21.0F }) {
            m.plateBox(x - 0.8F, 31.4F, 5.2F, x + 0.8F, 32.6F, 6.8F, TowerMesh.Mat.PAINT);
        }
        // The high side: a gas-insulated tube from the lid up toward the bus.
        m.turned(20.0F, 10.0F, new float[][] { { 31.4F, 3.0F }, { 32.2F, 3.0F }, { 32.2F, 2.2F }, { 40.0F, 2.2F } }, 10, TowerMesh.Ramp.ALUM, false, false);
        // The front: the tap changer's crank, the temperature gauge and the low-voltage cable box.
        m.swatchBox(10.0F, 18.0F, 1.6F, 13.0F, 21.0F, 2.5F, TowerMesh.RUBBER0 + 1);
        m.swatchBox(11.2F, 19.2F, 0.6F, 11.8F, 19.8F, 1.6F, TowerMesh.GALV0 + 6);
        m.swatchBox(11.5F, 19.2F, 0.2F, 14.0F, 19.8F, 0.6F, TowerMesh.GALV0 + 7);
        gauge(m, 17.0F, 22.0F, 2.5F);
        m.plateBox(14.0F, 4.0F, 0.6F, 22.0F, 12.0F, 2.5F, TowerMesh.Mat.PAINT);
        for (float x : new float[] { 15.6F, 18.0F, 20.4F }) {
            m.turned(x, 1.55F, new float[][] { { 1.6F, 0.7F }, { 4.0F, 0.7F } }, 6, TowerMesh.Ramp.RUBBER, false, false);
        }
        nameplate(m, 14.6F, 9.6F);
    }

    // A ring of bolt heads round a flange (radius r) at y, standing on it.
    private static void bolts(TowerMesh m, float x, float z, float y, float r, int count) {
        for (int i = 0; i < count; i++) {
            double a = Math.PI / count + i * 2.0 * Math.PI / count;
            float bx = x + (float) Math.cos(a) * (r - 0.45F), bz = z + (float) Math.sin(a) * (r - 0.45F);
            m.swatchBox(bx - 0.25F, y, bz - 0.25F, bx + 0.25F, y + 0.4F, bz + 0.25F, TowerMesh.GALV0 + 7);
        }
    }

    // An SF6 density gauge (or a temperature gauge) on the face at z looking north: a stub, the dial and its needle,
    // the green band.
    private static void gauge(TowerMesh m, float x, float y, float z) {
        m.swatchBox(x - 0.4F, y - 0.4F, z - 0.8F, x + 0.4F, y + 0.4F, z + 0.4F, TowerMesh.GALV0 + 5);
        m.swatchBox(x - 1.2F, y - 1.2F, z - 1.3F, x + 1.2F, y + 1.2F, z - 0.8F, TowerMesh.GALV0 + 7);
        m.swatchBox(x - 0.9F, y - 0.9F, z - 1.4F, x + 0.9F, y + 0.9F, z - 1.3F, TowerMesh.WHITE);
        m.swatchBox(x - 0.9F, y + 0.4F, z - 1.45F, x - 0.1F, y + 0.9F, z - 1.4F, TowerMesh.MARKER);
        m.swatchBox(x - 0.1F, y - 0.5F, z - 1.5F, x + 0.1F, y + 0.6F, z - 1.4F, TowerMesh.BLACK);
    }

    // --- Operators ---

    private static void operatorBody(TowerMesh m, Op op, int tier, OpState state) {
        int flag = switch (state) {
            case CLOSED -> TowerMesh.RED;
            case OPEN -> TowerMesh.GREEN;
            case TRIPPED -> TowerMesh.HAZARD;
        };
        boolean gis = tier >= 2;
        switch (op) {
            case BREAKER -> {
                // The handle on its boss (up closed, down open, out in the middle tripped) and the flag window by it.
                float s = gis ? 1.3F : tier == 1 ? 1.1F : 1.0F;
                m.swatchBox(-0.9F * s, -0.9F * s, -0.4F, 0.9F * s, 0.9F * s, 0, TowerMesh.RUBBER0 + 3);
                float[] tip = switch (state) {
                    case CLOSED -> new float[] { 0, 2.0F * s, -1.0F };
                    case OPEN -> new float[] { 0, -2.0F * s, -1.0F };
                    case TRIPPED -> new float[] { 0, 0.2F, -2.2F * s };
                };
                m.rod(new float[] { 0, 0, -0.3F }, tip, 0.7F * s, gis ? TowerMesh.GALV0 + 6 : TowerMesh.RUBBER0 + 4);
                m.swatchBox(-0.5F * s + tip[0], tip[1] - 0.5F * s, tip[2] - 0.4F, 0.5F * s + tip[0], tip[1] + 0.5F * s, tip[2] + 0.2F,
                        gis ? TowerMesh.GALV0 + 8 : TowerMesh.RUBBER0 + 2);
                float fy = gis ? -0.8F : 2.8F * s, fx = gis ? 3.4F : 0;
                m.swatchBox(fx - 1.3F * s, fy, -0.15F, fx + 1.3F * s, fy + 1.6F * s, 0, TowerMesh.BLACK);
                m.swatchBox(fx - 1.0F * s, fy + 0.3F * s, -0.25F, fx + 1.0F * s, fy + 1.3F * s, -0.15F, flag);
            }
            case SWITCH -> {
                // The load-break switch's rotary handle: a red bar on a boss, up and down closed, across open, half way
                // round with its fuse blown; the flag over it.
                m.swatchBox(-1.3F, -1.3F, -0.5F, 1.3F, 1.3F, 0, TowerMesh.RUBBER0 + 2);
                switch (state) {
                    case CLOSED -> m.swatchBox(-0.45F, -2.4F, -1.1F, 0.45F, 2.4F, -0.5F, TowerMesh.RED);
                    case OPEN -> m.swatchBox(-2.4F, -0.45F, -1.1F, 2.4F, 0.45F, -0.5F, TowerMesh.RED);
                    case TRIPPED -> m.rod(new float[] { -1.7F, -1.7F, -0.8F }, new float[] { 1.7F, 1.7F, -0.8F }, 0.9F, TowerMesh.RED);
                }
                m.swatchBox(-0.5F, -0.5F, -1.3F, 0.5F, 0.5F, -1.1F, TowerMesh.RUBBER0 + 4);
                m.swatchBox(-1.4F, 2.8F, -0.15F, 1.4F, 4.6F, 0, TowerMesh.BLACK);
                m.swatchBox(-1.1F, 3.1F, -0.25F, 1.1F, 4.3F, -0.15F, flag);
            }
            case DISCONNECTOR, EARTH -> {
                // A small flag window (an earthing switch closed shows the hazard yellow), and the socket its crank goes in.
                m.swatchBox(-1.3F, 0, -0.15F, 1.3F, 1.8F, 0, TowerMesh.BLACK);
                m.swatchBox(-1.0F, 0.3F, -0.25F, 1.0F, 1.5F, -0.15F, op == Op.EARTH && state == OpState.CLOSED ? TowerMesh.HAZARD : flag);
                m.swatchBox(-0.7F, -2.4F, -0.4F, 0.7F, -1.0F, 0, TowerMesh.GALV0 + 4);
                m.swatchBox(-0.3F, -2.0F, -0.5F, 0.3F, -1.4F, -0.4F, TowerMesh.BLACK);
            }
        }
    }

    // A round part along +x from `start` (its centre there), its profile {x along it, r}, `sides` facets with the ramp
    // across each (as TowerMesh.turned, for one lying on its side), a step in radius a flat ring.
    static void along(TowerMesh m, float[] start, float length, float[][] profile, int sides, TowerMesh.Ramp ramp, boolean capStart, boolean capEnd) {
        round(m, start, new float[] { 1, 0, 0 }, new float[] { 0, 1, 0 }, profile, sides, ramp, capStart, capEnd);
    }

    // A round part along axis from c (the profile's t along it), its ring in u and v = u x axis (the same hand as
    // TowerMesh.turned's: there axis y, u x, v z).
    static void round(TowerMesh m, float[] c, float[] axis, float[] u, float[][] profile, int sides, TowerMesh.Ramp ramp, boolean capStart, boolean capEnd) {
        float[] v = MeshBuilder.cross(u, axis);
        float rMax = 0;
        for (int k = 0; k + 1 < profile.length; k++) {
            float ta = profile[k][0], ra = profile[k][1], tb = profile[k + 1][0], rb = profile[k + 1][1];
            rMax = Math.max(rMax, Math.max(ra, rb));
            if (Math.abs(tb - ta) < 1.0E-4F) {
                if (Math.abs(rb - ra) > 1.0E-4F) {
                    disc(m, c, axis, u, v, ta, Math.min(ra, rb), Math.max(ra, rb), sides, rb < ra ? 1 : -1, ramp);
                }
                continue;
            }
            float[][] lo = ringAt(c, axis, u, v, ta, ra, sides), hi = ringAt(c, axis, u, v, tb, rb, sides);
            float roundness = (float) (2.0 * Math.PI * (ra + rb) / 2.0);
            for (int i = 0; i < sides; i++) {
                float[] p0 = lo[(i + 1) % sides], p1 = lo[i], p2 = hi[i], p3 = hi[(i + 1) % sides];
                float fw = (MeshBuilder.length(MeshBuilder.sub(p1, p0)) + MeshBuilder.length(MeshBuilder.sub(p2, p3))) / 2.0F;
                float len = (MeshBuilder.length(MeshBuilder.sub(p3, p0)) + MeshBuilder.length(MeshBuilder.sub(p2, p1))) / 2.0F;
                if (fw < 1.0E-3F || len < 1.0E-3F) {
                    continue;
                }
                m.rampFacet(p0, p1, p2, p3, i, sides, fw, len, roundness, ramp);
            }
        }
        if (capStart && profile[0][1] > 1.0E-3F) {
            disc(m, c, axis, u, v, profile[0][0], 0, profile[0][1], sides, -1, ramp);
        }
        float[] last = profile[profile.length - 1];
        if (capEnd && last[1] > 1.0E-3F) {
            disc(m, c, axis, u, v, last[0], 0, last[1], sides, 1, ramp);
        }
        float[] a = TowerMesh.add(c, TowerMesh.scale(axis, profile[0][0])), b = TowerMesh.add(c, TowerMesh.scale(axis, last[0]));
        float[] box = new float[6];
        for (int i = 0; i < 3; i++) {
            float spread = rMax * (float) Math.sqrt(Math.max(0, 1 - axis[i] * axis[i]));
            box[i] = Math.min(a[i], b[i]) - spread;
            box[i + 3] = Math.max(a[i], b[i]) + spread;
        }
        m.boxes.add(box);
    }

    private static float[][] ringAt(float[] c, float[] axis, float[] u, float[] v, float t, float r, int sides) {
        float[][] out = new float[sides][];
        for (int i = 0; i < sides; i++) {
            double angle = Math.PI / sides + i * 2.0 * Math.PI / sides;
            float cu = (float) Math.cos(angle) * r, sv = (float) Math.sin(angle) * r;
            out[i] = new float[] { c[0] + axis[0] * t + u[0] * cu + v[0] * sv, c[1] + axis[1] * t + u[1] * cu + v[1] * sv,
                    c[2] + axis[2] * t + u[2] * cu + v[2] * sv };
        }
        return out;
    }

    // A flat ring (a disc when inner is 0) across the axis at t, facing along it (facing 1) or back (-1): the ramp's lit
    // tone when it looks toward the light (up and to the north-west), its dark one when away.
    private static void disc(TowerMesh m, float[] c, float[] axis, float[] u, float[] v, float t, float inner, float outer, int sides, int facing,
            TowerMesh.Ramp ramp) {
        float[] n = TowerMesh.scale(axis, facing);
        boolean lit = n[1] - n[0] * 0.4F - n[2] * 0.7F > 0;
        float[] uv = TowerMesh.swatchUv(discTone(ramp, lit));
        float[][] o = ringAt(c, axis, u, v, t, outer, sides), in = ringAt(c, axis, u, v, t, Math.max(inner, 0), sides);
        for (int i = 0; i < sides; i++) {
            int j = (i + 1) % sides;
            float[] a = o[i], b = o[j], cc = in[j], d = in[i];
            float[] q = MeshBuilder.cross(MeshBuilder.sub(cc, a), MeshBuilder.sub(d, b));
            if (MeshBuilder.dot(q, n) >= 0) {
                m.quad(a, b, cc, d, uv);
            } else {
                m.quad(d, cc, b, a, uv);
            }
        }
    }

    private static int discTone(TowerMesh.Ramp ramp, boolean lit) {
        return switch (ramp) {
            case GALV -> lit ? TowerMesh.GALV0 + 7 : TowerMesh.GALV0 + 3;
            case PORCELAIN -> lit ? TowerMesh.PORC0 + 4 : TowerMesh.PORC0 + 1;
            case GLASS -> lit ? TowerMesh.GLASS0 + 4 : TowerMesh.GLASS0 + 1;
            case CAN -> lit ? TowerMesh.CAN0 + 4 : TowerMesh.CAN0 + 2;
            case COPPER -> TowerMesh.COPPER0 + 2;
            case PAINT -> lit ? TowerMesh.PAINT0 + 5 : TowerMesh.PAINT0 + 2;
            case ALUM -> lit ? TowerMesh.ALUM0 + 5 : TowerMesh.ALUM0 + 2;
            case SILICONE -> lit ? TowerMesh.SIL0 + 3 : TowerMesh.SIL0 + 1;
            case RUBBER -> lit ? TowerMesh.RUBBER0 + 3 : TowerMesh.RUBBER0 + 1;
        };
    }

    // ================================================================================================================
    // Shared
    // ================================================================================================================

    static TowerMesh.Built built(TowerMesh m) {
        List<float[]> shapes = new ArrayList<>(m.frame);
        shapes.addAll(m.insulators);
        return new TowerMesh.Built(m.solid.toArray(), List.copyOf(m.boxes), List.copyOf(shapes), List.copyOf(m.insulators), cells(shapes));
    }

    // The model turned upside down (half a turn about x, so its winding stays right) and lifted back into place: what
    // stood from 0 to height now hangs from 16 down to 16 - height (under the block it's mounted on, at y 16).
    static TowerMesh.Built flipped(TowerMesh.Built b, float height) {
        float[] q = b.solid().clone();
        for (int i = 0; i + 7 < q.length; i += 8) {
            q[i + 1] = 1.0F - q[i + 1];
            q[i + 2] = 1.0F - q[i + 2];
            q[i + 6] = -q[i + 6];
            q[i + 7] = -q[i + 7];
        }
        return new TowerMesh.Built(q, flipBoxes(b.boxes()), flipBoxes(b.shapes()), flipBoxes(b.insulators()), cells(flipBoxes(b.shapes())));
    }

    // The block cells (from the model's own block) its shapes reach into by more than a sliver, below it too.
    static List<int[]> cells(List<float[]> boxes) {
        java.util.Set<Long> seen = new java.util.LinkedHashSet<>();
        List<int[]> cells = new ArrayList<>();
        float e = 0.05F;
        for (float[] b : boxes) {
            for (int x = (int) Math.floor((b[0] + e) / 16); x <= (int) Math.floor((b[3] - e) / 16); x++) {
                for (int y = (int) Math.floor((b[1] + e) / 16); y <= (int) Math.floor((b[4] - e) / 16); y++) {
                    for (int z = (int) Math.floor((b[2] + e) / 16); z <= (int) Math.floor((b[5] - e) / 16); z++) {
                        if (seen.add(((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512))) {
                            cells.add(new int[] { x, y, z });
                        }
                    }
                }
            }
        }
        return cells;
    }

    private static List<float[]> flipBoxes(List<float[]> boxes) {
        List<float[]> out = new ArrayList<>(boxes.size());
        for (float[] b : boxes) {
            out.add(new float[] { b[0], 16 - b[4], 16 - b[5], b[3], 16 - b[1], 16 - b[2] });
        }
        return out;
    }
}
