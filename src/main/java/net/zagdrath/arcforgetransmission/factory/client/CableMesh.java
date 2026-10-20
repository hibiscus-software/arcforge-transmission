/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.factory.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Direction;
import net.zagdrath.arcforgetransmission.factory.CableNetwork;
import net.zagdrath.arcforgetransmission.factory.CableTrayBlock;

// Where tray cables lie and how they're drawn: round (eight-sided) jackets lying on a tray's rungs, slot by slot from its
// first rail (tools/factory_block_model_gen.py has the tray's numbers: standard and heavy-duty tray's slots 2 px apart
// from 3 px in, wide tray's 2.5 px apart from 1.75 in, six a layer, the second layer 2.6 px up). An LV or MV cable is one
// 2 px jacket; an HV or EHV cable three single cores in trefoil (1.5 or 2 px across: two on the rungs, one on top). Each
// is a tube along its path through the block (in by one side, out by another, or down to its termination's gland: a free
// drop), its jacket texture (block/cable/<tier>) wrapped round it, a texel a pixel. Pure geometry: px in the block's frame.
public final class CableMesh {
    private CableMesh() {}

    // A tube's quads: QUAD floats each, the normal then four corners of x, y, z (px), then round (the jacket's row, 0 to 16:
    // its texel down the jacket) and along (px).
    public static final int QUAD = 3 + 4 * 5, SIDES = 8;

    // The rung tops and slot centres by tray kind.
    static float rungTop(CableTrayBlock.Kind kind) {
        return kind == CableTrayBlock.Kind.STANDARD ? 6.0F : 6.5F;
    }

    static float slotCentre(CableTrayBlock.Kind kind, int slot) {
        return kind == CableTrayBlock.Kind.WIDE ? 1.75F + 2.5F * (slot % 6) : 3.0F + 2.0F * slot;
    }

    static float layer(CableTrayBlock.Kind kind, int slot) {
        return kind == CableTrayBlock.Kind.WIDE ? 2.6F * (slot / 6) : 0;
    }

    // Each core of a cable: its radius and its place across (lateral) and up from the rung tops (height), px.
    record Core(float radius, float lateral, float height) {}

    static List<Core> cores(int tier) {
        if (tier <= 1) {
            return List.of(new Core(1.0F, 0, 1.05F));
        }
        float r = tier == 2 ? 0.75F : 1.0F, top = tier == 2 ? 2.10F : 2.78F, low = tier == 2 ? 0.8F : 1.05F;
        return List.of(new Core(r, -r, low), new Core(r, r, low), new Core(r, 0, top));
    }

    // A cable's centre across the tray, by its first slot and how many it takes.
    static float lateral(CableTrayBlock.Kind kind, int slot, int tier) {
        int slots = tier <= 1 ? 1 : tier;
        float a = slotCentre(kind, slot), b = slotCentre(kind, slot + slots - 1);
        return (a + b) / 2;
    }

    // The tubes for every cable lying in a tray block.
    public static float[] build(CableTrayBlock.Kind kind, List<CableNetwork.Lie> lies) {
        List<float[]> quads = new ArrayList<>();
        for (CableNetwork.Lie lie : lies) {
            float lat = lateral(kind, lie.slot(), lie.tier()), base = rungTop(kind) + 0.05F + layer(kind, lie.slot());
            for (Core core : cores(lie.tier())) {
                List<float[]> path = path(lie, lat + core.lateral(), base + core.height());
                tube(quads, path, core.radius());
            }
        }
        float[] out = new float[quads.size() * QUAD];
        for (int i = 0; i < quads.size(); i++) {
            System.arraycopy(quads.get(i), 0, out, i * QUAD, QUAD);
        }
        return out;
    }

    // A cable hanging free from one gland straight to another (a drop with no tray: a tap-off's to its machine), px
    // from the block drawing it: its cores side by side across it.
    public static float[] drop(float[] from, float[] to, int tier) {
        List<float[]> quads = new ArrayList<>();
        float[] d = norm(new float[] { to[0] - from[0], to[1] - from[1], to[2] - from[2] });
        float[] across = Math.abs(d[1]) > 0.9F ? new float[] { 1, 0, 0 } : norm(cross(d, new float[] { 0, 1, 0 }));
        float[] up = norm(cross(across, d));
        for (Core core : cores(tier)) {
            float[] off = add(scale(across, core.lateral()), scale(up, core.height() - 1.05F));
            tube(quads, List.of(add(from, off), add(to, off)), core.radius());
        }
        float[] out = new float[quads.size() * QUAD];
        for (int i = 0; i < quads.size(); i++) {
            System.arraycopy(quads.get(i), 0, out, i * QUAD, QUAD);
        }
        return out;
    }

    // A core's centreline through the block: from where it comes in (a side's face, or its drop's far end) to the block's
    // middle line, and on to where it goes out. Across a horizontal run its place is `lat` from the run's first rail (north
    // of an east-west run, west of a north-south one) and `h` up; up a riser, `lat` across east-west.
    static List<float[]> path(CableNetwork.Lie lie, float lat, float h) {
        float[] in = end(lie.in(), lat, h, lie, true), out = end(lie.out(), lat, h, lie, false);
        float[] mid = middle(lie.in(), lie.out(), lat, h);
        List<float[]> pts = new ArrayList<>();
        pts.add(in);
        if (mid != null) {
            pts.add(mid);
        }
        pts.add(out);
        return pts;
    }

    // Where a core sits at a side's face (or, for a drop, its far end: the termination's gland).
    private static float[] end(int side, float lat, float h, CableNetwork.Lie lie, boolean first) {
        if (side < 0) {
            return new float[] { lie.dx() * 16, lie.dy() * 16, lie.dz() * 16 };
        }
        Direction d = Direction.values()[side];
        return switch (d) {
            case WEST -> new float[] { 0, h, lat };
            case EAST -> new float[] { 16, h, lat };
            case NORTH -> new float[] { lat, h, 0 };
            case SOUTH -> new float[] { lat, h, 16 };
            case UP -> new float[] { lat, 16, 8 };
            case DOWN -> new float[] { lat, 0, 8 };
        };
    }

    // The corner it turns at (null for a straight through, or a drop's own line).
    private static float[] middle(int in, int out, float lat, float h) {
        if (in < 0 || out < 0) {
            return new float[] { 8, h, 8 };
        }
        Direction a = Direction.values()[in], b = Direction.values()[out];
        if (a.getAxis() == b.getAxis()) {
            return null;
        }
        if (a.getAxis().isVertical() || b.getAxis().isVertical()) {
            return new float[] { lat, h, 8 };
        }
        // A horizontal turn: where the two runs' lines cross.
        float x = a.getAxis() == Direction.Axis.Z ? lat : b.getAxis() == Direction.Axis.Z ? lat : 8;
        float z = a.getAxis() == Direction.Axis.X ? lat : b.getAxis() == Direction.Axis.X ? lat : 8;
        return new float[] { x, h, z };
    }

    // A tube of `radius` along a polyline, SIDES facets round, each facet 16 / SIDES rows of the jacket; its corners shared
    // where it bends.
    static void tube(List<float[]> quads, List<float[]> pts, float radius) {
        int n = pts.size();
        float[][] us = new float[n][], vs = new float[n][];
        for (int i = 0; i < n; i++) {
            float[] p = pts.get(Math.max(0, i - 1)), q = pts.get(Math.min(n - 1, i + 1));
            float[] d = norm(new float[] { q[0] - p[0], q[1] - p[1], q[2] - p[2] });
            float[] ref = Math.abs(d[1]) > 0.9F ? new float[] { 1, 0, 0 } : new float[] { 0, 1, 0 };
            float[] u = norm(cross(d, ref));
            us[i] = u;
            vs[i] = norm(cross(u, d));
        }
        float along = 0;
        for (int i = 0; i + 1 < n; i++) {
            float[] a = pts.get(i), b = pts.get(i + 1);
            float len = (float) Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]) + (b[2] - a[2]) * (b[2] - a[2]));
            for (int k = 0; k < SIDES; k++) {
                // Facet 0 (the jacket's first rows: its stripe) centred on top, the rest round from it.
                double t0 = Math.PI / 2 - Math.PI / SIDES - 2 * Math.PI * k / SIDES, t1 = t0 - 2 * Math.PI / SIDES, tm = (t0 + t1) / 2;
                float[] a0 = ring(a, us[i], vs[i], radius, t0), a1 = ring(a, us[i], vs[i], radius, t1);
                float[] b0 = ring(b, us[i + 1], vs[i + 1], radius, t0), b1 = ring(b, us[i + 1], vs[i + 1], radius, t1);
                float[] normal = norm(add(scale(us[i], (float) Math.cos(tm)), scale(vs[i], (float) Math.sin(tm))));
                float u0 = 16.0F * k / SIDES, u1 = 16.0F * (k + 1) / SIDES;
                quads.add(quad(normal, a0, b0, b1, a1, u0, u1, along, along + len));
            }
            along += len;
        }
    }

    private static float[] ring(float[] c, float[] u, float[] v, float r, double t) {
        float cu = (float) Math.cos(t) * r, sv = (float) Math.sin(t) * r;
        return new float[] { c[0] + u[0] * cu + v[0] * sv, c[1] + u[1] * cu + v[1] * sv, c[2] + u[2] * cu + v[2] * sv };
    }

    private static float[] quad(float[] n, float[] p0, float[] p1, float[] p2, float[] p3, float u0, float u1, float v0, float v1) {
        // Wound to face out along n.
        float[] e1 = { p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2] }, e2 = { p3[0] - p0[0], p3[1] - p0[1], p3[2] - p0[2] };
        float[] f = cross(e1, e2);
        boolean flip = f[0] * n[0] + f[1] * n[1] + f[2] * n[2] < 0;
        float[][] c = flip ? new float[][] { p3, p2, p1, p0 } : new float[][] { p0, p1, p2, p3 };
        float[][] uv = flip ? new float[][] { { u1, v0 }, { u1, v1 }, { u0, v1 }, { u0, v0 } } : new float[][] { { u0, v0 }, { u0, v1 }, { u1, v1 }, { u1, v0 } };
        float[] out = new float[QUAD];
        out[0] = n[0];
        out[1] = n[1];
        out[2] = n[2];
        for (int i = 0; i < 4; i++) {
            int at = 3 + i * 5;
            out[at] = c[i][0];
            out[at + 1] = c[i][1];
            out[at + 2] = c[i][2];
            out[at + 3] = uv[i][0];
            out[at + 4] = uv[i][1];
        }
        return out;
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    private static float[] add(float[] a, float[] b) {
        return new float[] { a[0] + b[0], a[1] + b[1], a[2] + b[2] };
    }

    private static float[] scale(float[] a, float s) {
        return new float[] { a[0] * s, a[1] * s, a[2] * s };
    }

    private static float[] norm(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1.0E-6F ? new float[] { 0, 1, 0 } : new float[] { v[0] / l, v[1] / l, v[2] / l };
    }
}
