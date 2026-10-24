package org.upgradeguard.sentry.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.UnorderedList;

final class ComparisonPanel {
    private ComparisonPanel() {
    }

    static Component of(JsonNode comparison) {
        Div panel = new Div();
        JsonNode changes = comparison.path("changes");
        panel.add(section("Code", changes.path("code"), "added", "removed", "changed"));
        panel.add(section("Runtime and host", changes.path("runtime")));
        panel.add(section("Dependencies", changes.path("dependencies"), "added", "removed", "changed"));
        panel.add(section("Tests", changes.path("tests"), "newly_failing", "newly_skipped", "missing", "fixed", "added"));
        panel.add(new H3("Read this before drawing conclusions"));
        UnorderedList notes = new UnorderedList();
        comparison.path("warnings").forEach(item -> notes.add(new ListItem(item.asText())));
        panel.add(notes);
        return panel;
    }

    private static Component section(String title, JsonNode node, String... groups) {
        Div box = new Div();
        box.add(new H3(title));
        UnorderedList list = new UnorderedList();
        if (groups.length == 0) {
            node.forEach(item -> list.add(new ListItem(item.path("field").asText() + ": " + item.path("from").asText("none") + " to " + item.path("to").asText("none"))));
        } else {
            for (String group : groups) {
                JsonNode items = node.path(group);
                if (items.isArray() && !items.isEmpty()) {
                    ListItem entry = new ListItem(group.replace('_', ' ') + " (" + items.size() + "): " + summarise(items));
                    list.add(entry);
                }
            }
        }
        if (list.getChildren().findAny().isEmpty()) {
            box.add(new Paragraph("no differences"));
        } else {
            box.add(list);
        }
        return box;
    }

    private static String summarise(JsonNode items) {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (JsonNode item : items) {
            if (shown++ >= 8) {
                text.append(", and ").append(items.size() - 8).append(" more");
                break;
            }
            if (shown > 1) {
                text.append(", ");
            }
            text.append(item.isObject() ? item.path("name").asText() + " " + item.path("from").asText() + " to " + item.path("to").asText() : item.asText());
        }
        return text.toString();
    }
}
