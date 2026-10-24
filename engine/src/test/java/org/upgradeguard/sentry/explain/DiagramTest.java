package org.upgradeguard.sentry.explain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.upgradeguard.sentry.contract.Json;

class DiagramTest {
    private static ObjectNode evidence() throws IOException {
        return (ObjectNode) Json.read(Path.of("..", "contracts", "v1", "fixtures", "valid", "evidence_reject.json"));
    }

    @Test
    void theDiagramShowsTheChangeTheFailingTestAndTheFindingWithStableIds() throws IOException {
        DiagramData data = DiagramBuilder.fromEvidence(evidence());
        assertEquals("project", data.nodes().get(0).id());
        assertTrue(data.hasNode("dep:jinja2"));
        assertTrue(data.hasNode("test:1"));
        assertTrue(data.hasNode("finding:1"));
        Set<String> ids = new HashSet<>();
        data.nodes().forEach(node -> assertTrue(ids.add(node.id()), "duplicate id " + node.id()));
        data.edges().forEach(edge -> {
            assertTrue(data.hasNode(edge.source()));
            assertTrue(data.hasNode(edge.target()));
        });
        assertEquals(data, DiagramBuilder.fromEvidence(evidence()));
        assertTrue(data.limitations().stream().anyMatch(text -> text.contains("does not prove")));
    }

    @Test
    void largeEvidenceIsCutAndTheCutIsDisclosed() throws IOException {
        ObjectNode big = evidence();
        ArrayNode regressions = ((ObjectNode) big.path("diff").path("tests")).putArray("regressions");
        for (int number = 0; number < 30; number++) {
            regressions.add("tests/test_x.py::test_" + number);
        }
        DiagramData data = DiagramBuilder.fromEvidence(big);
        assertEquals(DiagramBuilder.MAX_TESTS, data.nodes().stream().filter(node -> node.type().equals("TEST")).count());
        assertTrue(data.limitations().stream().anyMatch(text -> text.contains("22 more failing test")));
    }

    @Test
    void mermaidTextIsPlainEscapedAndPassesItsOwnValidation() throws IOException {
        ObjectNode hostile = evidence();
        ((ObjectNode) hostile.path("diff").path("tests")).putArray("regressions").add("x\"]; click n1 href \"javascript:alert(1)\" <script>alert(1)</script> %%{init: {}}%%");
        DiagramData data = DiagramBuilder.fromEvidence(hostile);
        for (MermaidRenderer.Mode mode : MermaidRenderer.Mode.values()) {
            String text = MermaidRenderer.render(data, mode);
            assertTrue(text.startsWith("flowchart TD\n"));
            assertFalse(text.contains("<"));
            assertFalse(text.contains("javascript:"));
            assertFalse(text.contains("%%{"));
            assertFalse(text.contains("<script"));
            MermaidRenderer.validate(text);
        }
    }

    @Test
    void beforeAndAfterShowTheMatchingVersions() throws IOException {
        DiagramData data = DiagramBuilder.fromEvidence(evidence());
        assertTrue(MermaidRenderer.render(data, MermaidRenderer.Mode.BEFORE).contains("jinja2 3.0.3"));
        assertTrue(MermaidRenderer.render(data, MermaidRenderer.Mode.AFTER).contains("jinja2 3.1.6"));
        assertTrue(MermaidRenderer.render(data, MermaidRenderer.Mode.DIFF).contains("3.0.3 to 3.1.6"));
    }

    @Test
    void theValidatorOnlyAcceptsTheStatementsTheRendererWrites() {
        for (String bad : new String[] {"flowchart TD\n    click n1 \"http://evil\"\n", "flowchart TD\n%%{init: {\"securityLevel\":\"loose\"}}%%\n", "graph LR\n    a-->b\n",
                "flowchart TD\n    n1[\"<img src=x onerror=alert(1)>\"]:::plain\n", "flowchart TD\n    n1[\"a\"]:::plain\n    style n1 fill:red\n",
                "flowchart TD\n" + "    n1[\"a\"]:::plain\n".repeat(MermaidRenderer.MAX_LINES + 1), "flowchart TD\n" + "a".repeat(MermaidRenderer.MAX_SOURCE)}) {
            assertThrows(IllegalArgumentException.class, () -> MermaidRenderer.validate(bad), bad.substring(0, Math.min(40, bad.length())));
        }
    }

    @Test
    void harmlessWordsLikeClickAreFineInsideLabels() throws IOException {
        ObjectNode evidence = evidence();
        ((ObjectNode) evidence.path("diff").path("tests")).putArray("regressions").add("tests/test_click.py::test_click_href_handler");
        String text = MermaidRenderer.render(DiagramBuilder.fromEvidence(evidence), MermaidRenderer.Mode.DIFF);
        assertTrue(text.contains("test_click"));
        MermaidRenderer.validate(text);
    }

    @Test
    void labelsAreShortenedAndStripped() {
        String label = MermaidRenderer.escape("a".repeat(200) + "<b>");
        assertEquals(MermaidRenderer.MAX_LABEL, label.length());
        assertEquals("hello world", MermaidRenderer.escape("hello [world]()"));
    }

    @Test
    void anInferredNodeIsLabelledAsSuch() {
        DiagramData data = new DiagramData("1.0.0", java.util.List.of(new DiagramData.Node("project", "PROJECT", "app", "UNCHANGED", java.util.List.of(), true)), java.util.List.of(), java.util.List.of());
        assertTrue(MermaidRenderer.render(data, MermaidRenderer.Mode.DIFF).contains("app - inferred"));
        JsonNode unused = null;
        assertEquals(null, unused);
    }
}
