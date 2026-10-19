/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.tower.model;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

// Renders TowerMesh designs to PNGs for review (./gradlew renderPreviews, or with -Pdesigns=A,B): each from two sides
// (front left and back right, from a little above), on a ground of block squares for scale, textured from the real
// sheet, nearest texel, lit like the game's block faces. Written to build/previews/<design>.png and a contact sheet.
// Not a test: a tool, run outside the game.
public final class ModelPreview {
    private static final int VIEW = 560, SUPER = 2;
    private static BufferedImage sheet;

    private ModelPreview() {}

    public static void main(String[] args) throws IOException {
        sheet = ImageIO.read(new File(args[0]));
        File out = new File(args[1]);
        out.mkdirs();
        List<String> names = new ArrayList<>();
        if (args.length > 2 && !args[2].isBlank()) {
            names.addAll(List.of(args[2].split(",")));
        } else {
            for (TowerMesh.Design d : TowerMesh.Design.values()) {
                names.add(d.name());
            }
        }
        List<BufferedImage> all = new ArrayList<>();
        for (String name : names) {
            // NAME, NAME~ALT (its other state), NAME@lo:hi (only what's between those heights, blocks: a close-up).
            String base = name.contains("@") ? name.substring(0, name.indexOf('@')) : name;
            boolean alt = base.endsWith("~ALT");
            // POLE_LV / POLE_MV: the pole-mount transformers (blocks of their own, not designs).
            TowerMesh.Design design = base.startsWith("POLE_") ? null : TowerMesh.Design.valueOf(base.replace("~ALT", ""));
            TowerMesh.Built built = design == null ? (base.equals("POLE_MV") ? TowerMesh.poleTransformerMv() : TowerMesh.poleTransformer())
                    : alt ? TowerMesh.variant(design) : TowerMesh.get(design);
            float[] solid = built.solid();
            if (name.contains("@")) {
                String[] range = name.substring(name.indexOf('@') + 1).split(":");
                solid = crop(solid, Float.parseFloat(range[0]), Float.parseFloat(range[1]));
            }
            BufferedImage image = new BufferedImage(VIEW * 2, VIEW + 28, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            boolean ground = !name.contains("@");
            g.drawImage(render(solid, 35, 28, ground), 0, 28, null);
            g.drawImage(render(solid, 215, 28, ground), VIEW, 28, null);
            g.setColor(new Color(0x20, 0x22, 0x26));
            g.fillRect(0, 0, VIEW * 2, 28);
            g.setColor(Color.WHITE);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
            g.drawString(name + "   (" + (design == null ? 1 : design.height) + " blocks tall; grid squares are blocks)", 10, 20);
            g.dispose();
            ImageIO.write(image, "png", new File(out, name.toLowerCase().replaceAll("[~@:]", "_") + ".png"));
            all.add(image);
            System.out.println("rendered " + name);
        }
        if (all.size() > 1) {
            int cols = 2, w = VIEW, h = (VIEW + 28) / 2;
            int rows = (all.size() + cols - 1) / cols;
            BufferedImage contact = new BufferedImage(cols * w, rows * h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = contact.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < all.size(); i++) {
                g.drawImage(all.get(i), (i % cols) * w, (i / cols) * h, w, h, null);
            }
            g.dispose();
            ImageIO.write(contact, "png", new File(out, "contact_sheet.png"));
        }
    }

    // The quads wholly between two heights (blocks).
    private static float[] crop(float[] solid, float lo, float hi) {
        float[] out = new float[solid.length];
        int n = 0;
        for (int i = 0; i < solid.length; i += 32) {
            boolean inside = true;
            for (int k = 0; k < 4; k++) {
                float y = solid[i + k * 8 + 1];
                inside &= y >= lo && y <= hi;
            }
            if (inside) {
                System.arraycopy(solid, i, out, n, 32);
                n += 32;
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    // One view: orthographic, turned yaw degrees about the vertical and looking down pitch degrees.
    private static BufferedImage render(float[] solid, double yawDeg, double pitchDeg, boolean ground) {
        int size = VIEW * SUPER;
        double yaw = Math.toRadians(yawDeg), pitch = Math.toRadians(pitchDeg);
        double cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
        // The model's bounds, with a margin of ground round it.
        double minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9;
        for (int i = 0; i < solid.length; i += 8) {
            minX = Math.min(minX, solid[i]);
            maxX = Math.max(maxX, solid[i]);
            minY = Math.min(minY, solid[i + 1]);
            maxY = Math.max(maxY, solid[i + 1]);
            minZ = Math.min(minZ, solid[i + 2]);
            maxZ = Math.max(maxZ, solid[i + 2]);
        }
        int gx0 = (int) Math.floor(minX) - 1, gx1 = (int) Math.ceil(maxX) + 1, gz0 = (int) Math.floor(minZ) - 1, gz1 = (int) Math.ceil(maxZ) + 1;
        double cx = (gx0 + gx1) / 2.0, cz = (gz0 + gz1) / 2.0, cyMid = ((ground ? Math.min(0, minY) : minY) + maxY) / 2.0;
        // Screen bounds over the model and ground corners (or, close up, the model's own box).
        double gy = ground ? 0 : minY;
        double[][] probe = ground ? new double[][] { { gx0, gy, gz0 }, { gx1, gy, gz0 }, { gx0, gy, gz1 }, { gx1, gy, gz1 }, { minX, maxY, minZ },
                { maxX, maxY, maxZ }, { minX, maxY, maxZ }, { maxX, maxY, minZ } }
                : new double[][] { { minX, minY, minZ }, { maxX, minY, minZ }, { minX, minY, maxZ }, { maxX, minY, maxZ }, { minX, maxY, minZ },
                        { maxX, maxY, maxZ }, { minX, maxY, maxZ }, { maxX, maxY, minZ } };
        double sx0 = 1e9, sx1 = -1e9, sy0 = 1e9, sy1 = -1e9;
        for (double[] p : probe) {
            double[] s = project(p[0] - cx, p[1] - cyMid, p[2] - cz, cy, sy, cp, sp);
            sx0 = Math.min(sx0, s[0]);
            sx1 = Math.max(sx1, s[0]);
            sy0 = Math.min(sy0, s[1]);
            sy1 = Math.max(sy1, s[1]);
        }
        double scale = 0.92 * size / Math.max(sx1 - sx0, sy1 - sy0);
        double ox = size / 2.0 - (sx0 + sx1) / 2.0 * scale, oy = size / 2.0 + (sy0 + sy1) / 2.0 * scale;
        int[] colour = new int[size * size];
        double[] depth = new double[size * size];
        java.util.Arrays.fill(depth, Double.MAX_VALUE);
        java.util.Arrays.fill(colour, 0xFF9DB7CF);
        // The ground: block squares in two greens.
        for (int x = gx0; ground && x < gx1; x++) {
            for (int z = gz0; z < gz1; z++) {
                int c = ((x + z) & 1) == 0 ? 0xFF6F9A4C : 0xFF64904A;
                double[][] q = { { x, 0, z }, { x + 1, 0, z }, { x + 1, 0, z + 1 }, { x, 0, z + 1 } };
                double[][] s = new double[4][];
                for (int k = 0; k < 4; k++) {
                    s[k] = screen(q[k], cx, cyMid, cz, cy, sy, cp, sp, scale, ox, oy);
                    s[k][2] += 1.0E-3 * scale;
                }
                triangle(s[0], s[1], s[2], null, c, 1.0, colour, depth, size);
                triangle(s[0], s[2], s[3], null, c, 1.0, colour, depth, size);
            }
        }
        double[] light = normalize(new double[] { -0.4, 1.0, -0.7 });
        for (int i = 0; i < solid.length; i += 32) {
            double[][] s = new double[4][];
            double[][] uv = new double[4][];
            for (int k = 0; k < 4; k++) {
                int v = i + k * 8;
                s[k] = screen(new double[] { solid[v], solid[v + 1], solid[v + 2] }, cx, cyMid, cz, cy, sy, cp, sp, scale, ox, oy);
                uv[k] = new double[] { solid[v + 3], solid[v + 4] };
            }
            double nx = solid[i + 5], ny = solid[i + 6], nz = solid[i + 7];
            // The game's face shading (top full, bottom half, sides between) with a little directional light.
            double shade = Math.abs(ny) > 0.5 ? (ny > 0 ? 1.0 : 0.5) : 0.6 + 0.2 * Math.abs(nx);
            shade = Math.min(1.0, shade * (0.85 + 0.25 * Math.max(0, nx * light[0] + ny * light[1] + nz * light[2])));
            triangle(s[0], s[1], s[2], new double[][] { uv[0], uv[1], uv[2] }, 0, shade, colour, depth, size);
            triangle(s[0], s[2], s[3], new double[][] { uv[0], uv[2], uv[3] }, 0, shade, colour, depth, size);
        }
        BufferedImage big = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        big.setRGB(0, 0, size, size, colour, 0, size);
        BufferedImage small = new BufferedImage(VIEW, VIEW, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(big, 0, 0, VIEW, VIEW, null);
        g.dispose();
        return small;
    }

    private static double[] project(double x, double y, double z, double cy, double sy, double cp, double sp) {
        double rx = x * cy - z * sy, rz = x * sy + z * cy;
        // Screen x right, screen y up, depth away from the viewer.
        return new double[] { rx, y * cp + rz * sp, rz * cp - y * sp };
    }

    private static double[] screen(double[] p, double cx, double cyMid, double cz, double cy, double sy, double cp, double sp, double scale,
            double ox, double oy) {
        double[] r = project(p[0] - cx, p[1] - cyMid, p[2] - cz, cy, sy, cp, sp);
        return new double[] { ox + r[0] * scale, oy - r[1] * scale, r[2] * scale };
    }

    // A triangle, both faces (the meshes are closed; depth sorts them), textured or flat.
    private static void triangle(double[] a, double[] b, double[] c, double[][] uv, int flat, double shade, int[] colour, double[] depth, int size) {
        int x0 = (int) Math.max(0, Math.floor(Math.min(a[0], Math.min(b[0], c[0]))));
        int x1 = (int) Math.min(size - 1, Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int y0 = (int) Math.max(0, Math.floor(Math.min(a[1], Math.min(b[1], c[1]))));
        int y1 = (int) Math.min(size - 1, Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (b[1] - a[1]);
        if (Math.abs(area) < 1.0E-9) {
            return;
        }
        int sw = sheet.getWidth(), sh = sheet.getHeight();
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (c[0] - px) * (b[1] - py)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (a[0] - px) * (c[1] - py)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1.0E-6 || w1 < -1.0E-6 || w2 < -1.0E-6) {
                    continue;
                }
                double d = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int at = y * size + x;
                if (d >= depth[at]) {
                    continue;
                }
                int argb = flat;
                if (uv != null) {
                    double u = w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0], v = w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1];
                    int tx = Math.min(sw - 1, Math.max(0, (int) Math.floor(u * sw))), ty = Math.min(sh - 1, Math.max(0, (int) Math.floor(v * sh)));
                    argb = sheet.getRGB(tx, ty);
                    if ((argb >>> 24) < 128) {
                        continue;
                    }
                }
                depth[at] = d;
                int r = (int) (((argb >> 16) & 255) * shade), gr = (int) (((argb >> 8) & 255) * shade), bl = (int) ((argb & 255) * shade);
                colour[at] = 0xFF000000 | (r << 16) | (gr << 8) | bl;
            }
        }
    }

    private static double[] normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] { v[0] / l, v[1] / l, v[2] / l };
    }
}
