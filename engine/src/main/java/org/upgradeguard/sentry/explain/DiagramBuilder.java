package org.upgradeguard.sentry.explain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

public final class DiagramBuilder {
    public static final int MAX_DEPENDENCIES = 12;
    public static final int MAX_TESTS = 8;
    public static final int MAX_FINDINGS = 10;

    private DiagramBuilder() {
    }

    public static DiagramData fromEvidence(JsonNode evidence) {
        List<DiagramData.Node> nodes = new ArrayList<>();
        List<DiagramData.Edge> edges = new ArrayList<>();
        String project = evidence.path("request").path("project_id").asText("project");
        String target = evidence.path("request").path("change").path("package").asText("dependency");
        String from = evidence.path("request").path("change").path("from_version").asText("?");
        String to = evidence.path("request").path("change").path("to_version").asText("?");
        nodes.add(new DiagramData.Node("project", "PROJECT", project, "UNCHANGED", List.of(), false));
        String targetId = "dep:" + target;
        nodes.add(new DiagramData.Node(targetId, "DEPENDENCY", target + " " + from + " to " + to, "CHANGED", List.of(), false));
        edges.add(new DiagramData.Edge("project", targetId, "declares", List.of()));

        List<String> limitations = new ArrayList<>();
        limitations.add("Only the changed dependencies, failing tests and findings are drawn. This is not a map of the whole project.");
        limitations.add("An edge from a dependency to a test shows that the test failed after the change. It does not prove the dependency caused it.");
        int shown = 0;
        int hidden = 0;
        for (JsonNode change : evidence.path("diff").path("dependencies").path("changed")) {
            String name = change.path("name").asText();
            if (name.equals(target)) {
                continue;
            }
            if (shown < MAX_DEPENDENCIES) {
                String id = "dep:" + name;
                nodes.add(new DiagramData.Node(id, "DEPENDENCY", name + " " + change.path("from").asText() + " to " + change.path("to").asText(), "CHANGED", List.of(), false));
                edges.add(new DiagramData.Edge(targetId, id, "changed together with", List.of()));
                shown++;
            } else {
                hidden++;
            }
        }
        for (JsonNode added : evidence.path("diff").path("dependencies").path("added")) {
            if (shown < MAX_DEPENDENCIES) {
                String id = "dep:" + added.asText();
                nodes.add(new DiagramData.Node(id, "DEPENDENCY", added.asText() + " (new)", "ADDED", List.of(), false));
                edges.add(new DiagramData.Edge(targetId, id, "brought in by the change", List.of()));
                shown++;
            } else {
                hidden++;
            }
        }
        if (hidden > 0) {
            limitations.add(hidden + " more dependency change(s) are in the evidence but not drawn.");
        }
        int tests = 0;
        int hiddenTests = 0;
        for (JsonNode test : evidence.path("diff").path("tests").path("regressions")) {
            if (tests < MAX_TESTS) {
                String id = "test:" + (tests + 1);
                nodes.add(new DiagramData.Node(id, "TEST", test.asText(), "FAILING", List.of(), false));
                edges.add(new DiagramData.Edge(targetId, id, "tests failed after the change", List.of()));
                tests++;
            } else {
                hiddenTests++;
            }
        }
        if (hiddenTests > 0) {
            limitations.add(hiddenTests + " more failing test(s) are in the evidence but not drawn.");
        }
        int findings = 0;
        for (JsonNode finding : evidence.path("verdict").path("findings")) {
            if (!finding.path("severity").asText().equals("BLOCKING") || findings >= MAX_FINDINGS) {
                continue;
            }
            String id = "finding:" + (++findings);
            List<String> evidenceIds = new ArrayList<>();
            finding.path("evidence_ids").forEach(item -> evidenceIds.add(item.asText()));
            nodes.add(new DiagramData.Node(id, "FINDING", finding.path("code").asText(), "BLOCKING", evidenceIds, false));
            edges.add(new DiagramData.Edge(targetId, id, "led to", evidenceIds));
        }
        return new DiagramData(DiagramData.VERSION, nodes, edges, limitations);
    }
}
