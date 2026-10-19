/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// What TowerMesh builds its quads with (a trimmed copy of Arcforge's ModelMesh output side, which isn't public): the
// quad list and painting a flat polygon from sheet rectangles at 1 texel per px.
//
// Output: per vertex x, y, z (blocks from the model's origin), u, v (0-1 across the sheet), nx, ny, nz; four vertices a
// quad, wound counter-clockwise seen from outside. Pure Java, so the server can build the model for its shapes too.
public abstract class MeshBuilder {
    static final float PX = 16.0F;

    private final int sheetW, sheetH;
    Floats solid = new Floats();

    MeshBuilder(int sheetW, int sheetH) {
        this.sheetW = sheetW;
        this.sheetH = sheetH;
    }

    // Appends a quad: its points in px, mapped to blocks, with its normal (from its diagonals, so a quad with two points
    // the same, a triangle, still gets one).
    void quad(float[] p0, float[] p1, float[] p2, float[] p3, float[] uv) {
        float[][] points = { p0, p1, p2, p3 };
        float[] n = cross(sub(p2, p0), sub(p3, p1));
        float length = (float) Math.sqrt(dot(n, n));
        if (length < 1.0E-6F) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            float[] p = points[i];
            solid.add(p[0] / PX, p[1] / PX, p[2] / PX, uv[2 * i], uv[2 * i + 1], n[0] / length, n[1] / length, n[2] / length);
        }
    }

    // A flat convex polygon (st: its points across and up a plane from base) cut by rectangles {s0, s1, t0, t1, u, v},
    // each piece showing the sheet at 1:1 with texel (u, v) at its rectangle's top left (s0, t1). Right x up must look
    // out of the face.
    void paintPlane(List<float[]> st, float[] base, float[] right, float[] up, List<float[]> rects) {
        float area = 0;
        for (int i = 0; i < st.size(); i++) {
            float[] a = st.get(i), b = st.get((i + 1) % st.size());
            area += a[0] * b[1] - b[0] * a[1];
        }
        if (Math.abs(area) < 1.0E-6F) {
            return;
        }
        if (area < 0) {
            // Counter-clockwise across and up is counter-clockwise seen from outside.
            st = new ArrayList<>(st);
            Collections.reverse(st);
        }
        float sMin = Float.MAX_VALUE, sMax = -Float.MAX_VALUE, tMin = Float.MAX_VALUE, tMax = -Float.MAX_VALUE;
        for (float[] p : st) {
            sMin = Math.min(sMin, p[0]);
            sMax = Math.max(sMax, p[0]);
            tMin = Math.min(tMin, p[1]);
            tMax = Math.max(tMax, p[1]);
        }
        for (float[] rect : rects) {
            if (rect[1] <= sMin || rect[0] >= sMax || rect[3] <= tMin || rect[2] >= tMax) {
                continue;
            }
            List<float[]> clipped = clip(clip(clip(clip(st, 1, 0, -rect[0]), -1, 0, rect[1]), 0, 1, -rect[2]), 0, -1, rect[3]);
            // Corners the clipping repeats would fan out into quads with no area.
            List<float[]> piece = new ArrayList<>();
            for (float[] p : clipped) {
                float[] last = piece.isEmpty() ? null : piece.get(piece.size() - 1);
                if (last == null || Math.abs(p[0] - last[0]) + Math.abs(p[1] - last[1]) > 1.0E-3F) {
                    piece.add(p);
                }
            }
            if (piece.size() > 1 && Math.abs(piece.get(0)[0] - piece.get(piece.size() - 1)[0])
                    + Math.abs(piece.get(0)[1] - piece.get(piece.size() - 1)[1]) <= 1.0E-3F) {
                piece.remove(piece.size() - 1);
            }
            if (piece.size() < 3) {
                continue;
            }
            float[][] p3 = new float[piece.size()][];
            float[] uv = new float[piece.size() * 2];
            for (int k = 0; k < piece.size(); k++) {
                float s = piece.get(k)[0], t = piece.get(k)[1];
                p3[k] = new float[] { base[0] + right[0] * s + up[0] * t, base[1] + right[1] * s + up[1] * t, base[2] + right[2] * s + up[2] * t };
                // Texel rows run down from the rectangle's top.
                uv[2 * k] = (rect[4] + s - rect[0]) / sheetW;
                uv[2 * k + 1] = (rect[5] + rect[3] - t) / sheetH;
            }
            // A fan of quads (a triangle repeats its last point); slivers with no area to draw are left out.
            for (int k = 1; k + 1 < piece.size(); k += 2) {
                int c = k + 2 < piece.size() ? k + 2 : k + 1;
                float fanArea = length(cross(sub(p3[k], p3[0]), sub(p3[k + 1], p3[0]))) + length(cross(sub(p3[k + 1], p3[0]), sub(p3[c], p3[0])));
                if (fanArea < 0.02F) {
                    continue;
                }
                quad(p3[0], p3[k], p3[k + 1], p3[c], new float[] { uv[0], uv[1], uv[2 * k], uv[2 * k + 1], uv[2 * k + 2], uv[2 * k + 3], uv[2 * c],
                        uv[2 * c + 1] });
            }
        }
    }

    // The part of a polygon where a s + b t + c >= 0.
    static List<float[]> clip(List<float[]> poly, float a, float b, float c) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            float[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
            float dp = a * p[0] + b * p[1] + c, dq = a * q[0] + b * q[1] + c;
            if (dp >= 0) {
                out.add(p);
            }
            if (dp >= 0 != dq >= 0) {
                float k = dp / (dp - dq);
                out.add(new float[] { p[0] + (q[0] - p[0]) * k, p[1] + (q[1] - p[1]) * k });
            }
        }
        return out;
    }

    // Where the ramp period px across starts on a ramp row (2 + 3 + ... + (period - 1)).
    static int rampX(int period) {
        return (period - 1) * period / 2 - 1;
    }

    static float[] bilinear(float[] p0, float[] p1, float[] p2, float[] p3, float s, float t) {
        float[] out = new float[3];
        for (int k = 0; k < 3; k++) {
            float bottom = p0[k] + (p1[k] - p0[k]) * s, top = p3[k] + (p2[k] - p3[k]) * s;
            out[k] = bottom + (top - bottom) * t;
        }
        return out;
    }

    static float length(float[] v) {
        return (float) Math.sqrt(dot(v, v));
    }

    static float[] normalize(float[] v) {
        float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1.0E-9F ? v : new float[] { v[0] / l, v[1] / l, v[2] / l };
    }

    static float[] sub(float[] a, float[] b) {
        return new float[] { a[0] - b[0], a[1] - b[1], a[2] - b[2] };
    }

    static float[] cross(float[] a, float[] b) {
        return new float[] { a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0] };
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    // A growable float array.
    static final class Floats {
        private float[] data = new float[4096];
        private int size;

        void add(float... values) {
            if (size + values.length > data.length) {
                data = Arrays.copyOf(data, Math.max(data.length * 2, size + values.length));
            }
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }

        int size() {
            return size;
        }

        float get(int i) {
            return data[i];
        }

        void set(int i, float value) {
            data[i] = value;
        }
    }
}
