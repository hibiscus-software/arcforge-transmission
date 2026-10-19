/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// One network's shape, worked out once per layout (GridNetwork keeps it) for the flow solver: its nodes and wires by
// index, and either
//  - a tree (no loops: most lines): rooted at node 0, each node's parent wire, a top-down order, depths and ancestor
//    tables (so the lowest common ancestor of two nodes is a few steps), which turn every wire's current into one pass
//    over the tree; or
//  - a meshed network (a loop, a ring bus, parallel wires): zero-resistance links (jumpers) merged into super-nodes,
//    each with a spanning tree of its links, the rest of the wires between super-nodes, for a resistive solve that
//    splits current between parallel paths.
public final class NetworkTopology {
    final int n, m;
    final long[] ids;
    final Map<Long, Integer> index;
    final GridEdge[] edges;
    final int[] ea, eb;
    final boolean tree;
    // Tree: parent node, the wire to it, whether the node is that wire's a end, top-down order, depth, ancestors (2^k up).
    final int[] parent, parentEdge, order, depth;
    final boolean[] childIsA;
    final int[][] up;
    // Meshed: each node's super-node, how many there are, the wires between super-nodes (by wire index), and in each
    // super-node a spanning tree of its zero-resistance links (each node's link toward its super-node's root, -1 at the
    // root) and an order with the root first.
    final int[] superOf;
    final int supers;
    final int[] meshEdges;
    final int[] linkParent, linkEdge, linkOrder;
    final boolean[] linkChildIsA;

    NetworkTopology(List<Long> nodes, List<GridEdge> networkEdges) {
        n = nodes.size();
        m = networkEdges.size();
        ids = new long[n];
        index = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            ids[i] = nodes.get(i);
            index.put(ids[i], i);
        }
        edges = networkEdges.toArray(new GridEdge[0]);
        ea = new int[m];
        eb = new int[m];
        List<List<int[]>> adjacent = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjacent.add(new ArrayList<>(2));
        }
        for (int e = 0; e < m; e++) {
            ea[e] = index.get(edges[e].a());
            eb[e] = index.get(edges[e].b());
            adjacent.get(ea[e]).add(new int[] { eb[e], e });
            adjacent.get(eb[e]).add(new int[] { ea[e], e });
        }
        tree = m == n - 1;
        if (tree) {
            parent = new int[n];
            parentEdge = new int[n];
            childIsA = new boolean[n];
            order = new int[n];
            depth = new int[n];
            Arrays.fill(parent, -1);
            Arrays.fill(parentEdge, -1);
            boolean[] seen = new boolean[n];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(0);
            seen[0] = true;
            int k = 0;
            while (!queue.isEmpty()) {
                int v = queue.poll();
                order[k++] = v;
                for (int[] next : adjacent.get(v)) {
                    if (!seen[next[0]]) {
                        seen[next[0]] = true;
                        parent[next[0]] = v;
                        parentEdge[next[0]] = next[1];
                        childIsA[next[0]] = ea[next[1]] == next[0];
                        depth[next[0]] = depth[v] + 1;
                        queue.add(next[0]);
                    }
                }
            }
            int levels = 1;
            while ((1 << levels) < n) {
                levels++;
            }
            up = new int[levels][n];
            for (int v = 0; v < n; v++) {
                up[0][v] = parent[v] < 0 ? v : parent[v];
            }
            for (int j = 1; j < levels; j++) {
                for (int v = 0; v < n; v++) {
                    up[j][v] = up[j - 1][up[j - 1][v]];
                }
            }
            superOf = null;
            supers = 0;
            meshEdges = null;
            linkParent = linkEdge = linkOrder = null;
            linkChildIsA = null;
        } else {
            parent = parentEdge = order = depth = null;
            childIsA = null;
            up = null;
            // Super-nodes: nodes joined by zero-resistance links, each with a spanning tree of them.
            superOf = new int[n];
            Arrays.fill(superOf, -1);
            linkParent = new int[n];
            linkEdge = new int[n];
            linkChildIsA = new boolean[n];
            linkOrder = new int[n];
            Arrays.fill(linkParent, -1);
            Arrays.fill(linkEdge, -1);
            int count = 0, k = 0;
            for (int start = 0; start < n; start++) {
                if (superOf[start] >= 0) {
                    continue;
                }
                ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(start);
                superOf[start] = count;
                while (!queue.isEmpty()) {
                    int v = queue.poll();
                    linkOrder[k++] = v;
                    for (int[] next : adjacent.get(v)) {
                        if (zero(edges[next[1]]) && superOf[next[0]] < 0) {
                            superOf[next[0]] = count;
                            linkParent[next[0]] = v;
                            linkEdge[next[0]] = next[1];
                            linkChildIsA[next[0]] = ea[next[1]] == next[0];
                            queue.add(next[0]);
                        }
                    }
                }
                count++;
            }
            supers = count;
            int[] mesh = new int[m];
            int c = 0;
            for (int e = 0; e < m; e++) {
                if (!zero(edges[e])) {
                    mesh[c++] = e;
                }
            }
            meshEdges = Arrays.copyOf(mesh, c);
        }
    }

    // A wire with no resistance to speak of: a jumper (a device's or a bush's link) or a wire of no length.
    static boolean zero(GridEdge edge) {
        return edge.isJumper() || edge.length() <= 1.0E-9;
    }

    // The lowest common ancestor of two nodes (a tree).
    int lca(int a, int b) {
        if (depth[a] < depth[b]) {
            int t = a;
            a = b;
            b = t;
        }
        int diff = depth[a] - depth[b];
        for (int j = 0; diff > 0; j++, diff >>= 1) {
            if ((diff & 1) != 0) {
                a = up[j][a];
            }
        }
        if (a == b) {
            return a;
        }
        for (int j = up.length - 1; j >= 0; j--) {
            if (up[j][a] != up[j][b]) {
                a = up[j][a];
                b = up[j][b];
            }
        }
        return parent[a];
    }
}
