package org.upgradeguard.sentry.explain;

import java.util.List;

public record DiagramData(String schemaVersion, List<Node> nodes, List<Edge> edges, List<String> limitations) {
    public static final String VERSION = "1.0.0";

    public record Node(String id, String type, String label, String change, List<String> evidenceIds, boolean inferred) {
    }

    public record Edge(String source, String target, String relation, List<String> evidenceIds) {
    }

    public boolean hasNode(String id) {
        return nodes.stream().anyMatch(node -> node.id().equals(id));
    }
}
