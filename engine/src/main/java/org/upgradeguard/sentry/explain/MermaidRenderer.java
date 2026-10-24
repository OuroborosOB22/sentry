package org.upgradeguard.sentry.explain;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MermaidRenderer {
    public static final int MAX_LABEL = 60;
    public static final int MAX_SOURCE = 20_000;
    public static final int MAX_LINES = 400;
    private static final String TEXT = "[A-Za-z0-9 ._:/-]*";
    private static final List<Pattern> ALLOWED_LINES = List.of(
            Pattern.compile("flowchart TD"),
            Pattern.compile("    n\\d+\\[\"" + TEXT + "\"]:::(changed|failing|plain)"),
            Pattern.compile("    n\\d+ -->\\|" + TEXT + "\\| n\\d+"),
            Pattern.compile("    classDef (changed|failing|plain) fill:#[0-9a-f]{6},stroke:#[0-9a-f]{3,6};"));

    public enum Mode {
        BEFORE, AFTER, DIFF
    }

    private MermaidRenderer() {
    }

    public record Rendered(String text, Map<String, String> nodeIds) {
    }

    public static String render(DiagramData data, Mode mode) {
        return renderWithIds(data, mode).text();
    }

    public static Rendered renderWithIds(DiagramData data, Mode mode) {
        Map<String, String> ids = new HashMap<>();
        StringBuilder text = new StringBuilder("flowchart TD\n");
        int counter = 0;
        for (DiagramData.Node node : data.nodes()) {
            if (!visible(node, mode)) {
                continue;
            }
            String id = "n" + (++counter);
            ids.put(node.id(), id);
            text.append("    ").append(id).append("[\"").append(label(node, mode)).append("\"]:::").append(style(node)).append('\n');
        }
        for (DiagramData.Edge edge : data.edges()) {
            if (ids.containsKey(edge.source()) && ids.containsKey(edge.target())) {
                text.append("    ").append(ids.get(edge.source())).append(" -->|").append(escape(edge.relation())).append("| ").append(ids.get(edge.target())).append('\n');
            }
        }
        text.append("    classDef changed fill:#fff3cd,stroke:#8a6d3b;\n");
        text.append("    classDef failing fill:#f8d7da,stroke:#842029;\n");
        text.append("    classDef plain fill:#eef2f7,stroke:#445;\n");
        Map<String, String> reverse = new java.util.LinkedHashMap<>();
        data.nodes().forEach(node -> {
            if (ids.containsKey(node.id())) {
                reverse.put(ids.get(node.id()), node.id());
            }
        });
        return new Rendered(validate(text.toString()), reverse);
    }

    private static boolean visible(DiagramData.Node node, Mode mode) {
        return switch (mode) {
            case DIFF -> true;
            case BEFORE -> !node.change().equals("ADDED");
            case AFTER -> true;
        };
    }

    private static String label(DiagramData.Node node, Mode mode) {
        String text = node.label();
        if (node.type().equals("DEPENDENCY") && mode != Mode.DIFF) {
            int to = text.lastIndexOf(" to ");
            int from = text.lastIndexOf(' ', to - 1);
            if (to > 0 && from > 0) {
                text = text.substring(0, from) + " " + (mode == Mode.BEFORE ? text.substring(from + 1, to) : text.substring(to + 4));
            }
        }
        if (node.inferred()) {
            text = text + " - inferred";
        }
        return escape(text);
    }

    private static String style(DiagramData.Node node) {
        return switch (node.change()) {
            case "CHANGED", "ADDED" -> "changed";
            case "FAILING", "BLOCKING" -> "failing";
            default -> "plain";
        };
    }

    public static String escape(String text) {
        StringBuilder clean = new StringBuilder();
        for (char symbol : text.toCharArray()) {
            if (Character.isLetterOrDigit(symbol) || symbol == ' ' || symbol == '.' || symbol == '-' || symbol == '_' || symbol == ':' || symbol == '/') {
                clean.append(symbol);
            } else {
                clean.append(' ');
            }
        }
        String squeezed = clean.toString().replaceAll("(?i)javascript:", "javascript ").replaceAll("\\s+", " ").trim();
        return squeezed.length() > MAX_LABEL ? squeezed.substring(0, MAX_LABEL - 3) + "..." : squeezed;
    }

    public static String validate(String source) {
        if (source.length() > MAX_SOURCE) {
            throw new IllegalArgumentException("the diagram is too large");
        }
        String[] lines = source.split("\n", -1);
        if (lines.length > MAX_LINES) {
            throw new IllegalArgumentException("the diagram has too many lines");
        }
        if (!lines[0].equals("flowchart TD")) {
            throw new IllegalArgumentException("the diagram must be a flowchart");
        }
        for (int number = 0; number < lines.length; number++) {
            String line = lines[number];
            if (line.isEmpty() && number == lines.length - 1) {
                continue;
            }
            if (ALLOWED_LINES.stream().noneMatch(pattern -> pattern.matcher(line).matches())) {
                throw new IllegalArgumentException("line " + (number + 1) + " is not an allowed diagram statement");
            }
        }
        return source;
    }
}
