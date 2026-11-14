package com.orion.controller.nib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The topology as an adjacency matrix: cell [i][j] holds the direct link from switch i to switch j, or null.
 * Immutable: the NIB builds a new graph on every change, so readers never see a half-updated matrix.
 */
public final class TopologyGraph {

    private final List<String> nodes;
    private final Map<String, Integer> index = new HashMap<>();
    private final LinkInfo[][] matrix;

    TopologyGraph(List<String> nodes) {
        this.nodes = List.copyOf(nodes);
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }
        this.matrix = new LinkInfo[nodes.size()][nodes.size()];
    }

    public static TopologyGraph empty() {
        return new TopologyGraph(List.of());
    }

    /** Only used while the NIB builds a graph. */
    void set(LinkInfo link) {
        Integer i = index.get(link.fromSwitch());
        Integer j = index.get(link.toSwitch());
        if (i != null && j != null) {
            matrix[i][j] = link;
        }
    }

    public List<String> nodes() {
        return nodes;
    }

    public LinkInfo get(String from, String to) {
        Integer i = index.get(from);
        Integer j = index.get(to);
        return i == null || j == null ? null : matrix[i][j];
    }

    /** Links leaving the given switch. */
    public List<LinkInfo> neighbours(String switchId) {
        Integer i = index.get(switchId);
        if (i == null) {
            return List.of();
        }
        List<LinkInfo> out = new ArrayList<>();
        for (LinkInfo l : matrix[i]) {
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    /** Every link once (from the switch with the smaller index). */
    public List<LinkInfo> links() {
        List<LinkInfo> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            for (int j = i + 1; j < nodes.size(); j++) {
                if (matrix[i][j] != null) {
                    out.add(matrix[i][j]);
                }
            }
        }
        return out;
    }

    /** The matrix as link ids (null = no direct link), for display. */
    public List<List<String>> matrixOfLinkIds() {
        List<List<String>> rows = new ArrayList<>();
        for (LinkInfo[] row : matrix) {
            List<String> cells = new ArrayList<>();
            for (LinkInfo cell : row) {
                cells.add(cell == null ? null : cell.linkId());
            }
            rows.add(Collections.unmodifiableList(cells));
        }
        return rows;
    }
}
