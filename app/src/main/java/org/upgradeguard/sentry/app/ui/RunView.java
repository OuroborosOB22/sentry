package org.upgradeguard.sentry.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.StreamResource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.upgradeguard.sentry.app.explain.InsightService;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.risk.RiskModelProvider;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.contract.Json;
import org.upgradeguard.sentry.explain.MermaidRenderer;

@Route(value = "run/:id", layout = MainLayout.class)
@PageTitle("Run | Sentry")
public class RunView extends VerticalLayout implements BeforeEnterObserver {
    private final JobService jobs;
    private final JobRepository jobRows;
    private final InsightService insight;
    private final RiskModelProvider risk;
    private Rows.Job job;
    private final Div status = new Div();
    private final Div details = new Div();
    private com.vaadin.flow.shared.Registration pollRegistration;
    private JsonNode diagramJson;
    private MermaidDiagram diagram;
    private Div drawer;

    public RunView(JobService jobs, JobRepository jobRows, InsightService insight, RiskModelProvider risk) {
        this.jobs = jobs;
        this.jobRows = jobRows;
        this.insight = insight;
        this.risk = risk;
        addClassName("page");
        setPadding(false);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        Optional<Rows.Job> found = jobs.find(event.getRouteParameters().get("id").orElse("")).filter(item -> UiAccess.allowed(item.projectId()));
        if (found.isEmpty()) {
            add(new H2("Run not found"), new RouterLink("Back to projects", ProjectsView.class));
            return;
        }
        job = found.get();
        add(new RouterLink("Back to the project", ProjectView.class, new RouteParameters("id", job.projectId())));
        add(new H2(titleOf(job)), status, details);
        refresh();
    }

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent attachEvent) {
        if (job != null && !job.finished()) {
            UI ui = attachEvent.getUI();
            ui.setPollInterval(1000);
            pollRegistration = ui.addPollListener(event -> {
                job = jobs.find(job.id()).orElse(job);
                refresh();
                if (job.finished()) {
                    ui.setPollInterval(-1);
                    if (pollRegistration != null) {
                        pollRegistration.remove();
                    }
                }
            });
        }
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent detachEvent) {
        detachEvent.getUI().setPollInterval(-1);
        if (pollRegistration != null) {
            pollRegistration.remove();
        }
    }

    private static String titleOf(Rows.Job job) {
        return switch (job.operation()) {
            case "SNAPSHOT" -> "Snapshot";
            case "REHEARSE_UPGRADE" -> "Try a change";
            case "REHEARSE_RECOVERY" -> "Test recovery";
            default -> job.operation();
        };
    }

    private void refresh() {
        status.removeAll();
        status.addClassName("card");
        Span badge = new Span(job.verdict() != null ? job.verdict().replace('_', ' ') : job.status());
        badge.addClassNames("badge", (job.verdict() != null ? job.verdict() : "unknown").toLowerCase());
        FlexLayout chips = new FlexLayout();
        chips.addClassName("stage-row");
        chip(chips, "state: " + job.status());
        chip(chips, "where: " + job.executionProvider());
        if (job.stage() != null) {
            chip(chips, "stage: " + job.stage());
        }
        status.add(badge, chips);
        if (!job.finished()) {
            ProgressBar progress = new ProgressBar();
            progress.setIndeterminate(true);
            progress.getElement().setAttribute("aria-label", "The job is running");
            Button cancel = new Button("Cancel", event -> {
                jobs.cancel(job.id());
                Notification.show("Cancelling. The running process is stopped within a few seconds.");
            });
            cancel.addThemeVariants(ButtonVariant.LUMO_ERROR);
            status.add(progress, cancel);
        }
        if (job.error() != null) {
            Paragraph error = new Paragraph(job.error());
            error.getElement().setAttribute("role", "alert");
            error.getStyle().set("color", "var(--lumo-error-text-color)");
            status.add(error);
        }
        details.removeAll();
        if (job.status().equals("SUCCEEDED") && job.operation().equals("SNAPSHOT")) {
            details.add(new Div(new Paragraph("The snapshot was recorded. Open the project to compare it with an earlier one.")));
        } else if (job.evidencePath() != null) {
            showEvidence();
        } else if (job.finished()) {
            details.add(new Div(new Paragraph("There is no evidence for this run. " + (job.status().equals("CANCELLED") ? "It was cancelled." : "See the message above."))));
        } else {
            details.add(new Paragraph("Waiting for the first results. The page updates by itself."));
        }
    }

    private static void chip(FlexLayout row, String text) {
        Span chip = new Span(text);
        chip.addClassName("stage-chip");
        row.add(chip);
    }

    private void showEvidence() {
        JsonNode evidence;
        try {
            evidence = jobs.evidence(job.id()).orElse(null);
        } catch (IOException unreadable) {
            details.add(new Paragraph("The stored evidence could not be read."));
            return;
        }
        if (evidence == null) {
            details.add(new Paragraph("The evidence file is missing."));
            return;
        }
        details.add(answerCard(evidence), tabsCard(evidence), coverageCard(evidence), diagramCard(), explanationCard());
        if (risk.enabled() && evidence.path("request").path("change").path("ecosystem").asText().equals("MAVEN")) {
            details.add(riskCard(evidence.path("request").path("change")));
        }
    }

    private Div answerCard(JsonNode evidence) {
        Div card = new Div();
        card.addClassName("card");
        JsonNode change = evidence.path("request").path("change");
        card.add(new H3("Short answer"));
        card.add(new Paragraph(change.path("package").asText() + " " + change.path("from_version").asText() + " to " + change.path("to_version").asText()
                + ": " + evidence.path("verdict").path("decision").asText().replace('_', ' ') + "."));
        UnorderedList reasons = new UnorderedList();
        for (JsonNode finding : evidence.path("verdict").path("findings")) {
            ListItem item = new ListItem(finding.path("code").asText() + " (" + finding.path("severity").asText().toLowerCase() + "): " + finding.path("message").asText());
            reasons.add(item);
        }
        card.add(reasons);
        card.add(new Paragraph("Test success is not proof of production safety, licence compliance or the absence of malware."));
        return card;
    }

    private Div tabsCard(JsonNode evidence) {
        Div card = new Div();
        card.addClassName("card");
        Tab code = new Tab("Code");
        Tab environment = new Tab("Environment");
        Tab dependencies = new Tab("Dependencies");
        Tab tests = new Tab("Tests");
        Tabs tabs = new Tabs(code, environment, dependencies, tests);
        tabs.getElement().setAttribute("aria-label", "Before and after comparison");
        Div content = new Div();
        Runnable show = () -> {
            content.removeAll();
            Tab selected = tabs.getSelectedTab();
            if (selected == code) {
                content.add(new Paragraph("Both sides were built from the same source copy, so there are no code differences in a rehearsal. "
                        + "Source differences between two moments are shown when you compare snapshots."));
            } else if (selected == environment) {
                content.add(environmentPanel(evidence));
            } else if (selected == dependencies) {
                content.add(dependenciesPanel(evidence));
            } else {
                content.add(testsPanel(evidence));
            }
        };
        tabs.addSelectedChangeListener(event -> show.run());
        tabs.setSelectedTab(dependencies);
        show.run();
        card.add(new H3("Before and after"), tabs, content);
        return card;
    }

    private Div environmentPanel(JsonNode evidence) {
        Div panel = new Div();
        for (String side : new String[] {"before", "after"}) {
            JsonNode node = evidence.path(side);
            panel.add(new Paragraph(side + ": install " + node.path("install").path("outcome").asText() + ", build " + node.path("build").path("outcome").asText()
                    + ", tests " + node.path("tests").path("stage").path("outcome").asText() + ", " + node.path("environment").path("os").asText() + " "
                    + node.path("environment").path("architecture").asText() + ", runtimes " + node.path("environment").path("runtimes").toString()));
        }
        return panel;
    }

    private Div dependenciesPanel(JsonNode evidence) {
        Div panel = new Div();
        JsonNode changes = evidence.path("diff").path("dependencies");
        record Row(String name, String from, String to, String kind) {
        }
        List<Row> rows = new ArrayList<>();
        changes.path("changed").forEach(item -> rows.add(new Row(item.path("name").asText(), item.path("from").asText(), item.path("to").asText(), "changed")));
        changes.path("added").forEach(item -> rows.add(new Row(item.asText(), "", "new", "added")));
        changes.path("removed").forEach(item -> rows.add(new Row(item.asText(), "gone", "", "removed")));
        if (rows.isEmpty()) {
            panel.add(new Paragraph("The resolved dependencies are identical on both sides."));
            return panel;
        }
        Grid<Row> grid = new Grid<>();
        grid.addColumn(Row::name).setHeader("Dependency");
        grid.addColumn(Row::from).setHeader("Before");
        grid.addColumn(Row::to).setHeader("After");
        grid.addColumn(Row::kind).setHeader("Change");
        grid.setItems(rows);
        grid.setAllRowsVisible(true);
        panel.add(grid);
        return panel;
    }

    private Div testsPanel(JsonNode evidence) {
        Div panel = new Div();
        JsonNode tests = evidence.path("diff").path("tests");
        panel.add(list("Passed before, not passing after", tests.path("regressions")));
        panel.add(list("New tests that fail", tests.path("new_failing")));
        panel.add(list("Missing or skipped after", tests.path("missing_or_skipped")));
        panel.add(new Paragraph("Tests that were already failing before the change are not counted against it."));
        return panel;
    }

    private static Div list(String title, JsonNode items) {
        Div box = new Div();
        box.add(new H3(title + " (" + items.size() + ")"));
        UnorderedList list = new UnorderedList();
        int shown = 0;
        for (JsonNode item : items) {
            if (shown++ >= 50) {
                list.add(new ListItem("and " + (items.size() - 50) + " more in the evidence file"));
                break;
            }
            list.add(new ListItem(item.asText()));
        }
        if (items.isEmpty()) {
            list.add(new ListItem("none"));
        }
        box.add(list);
        return box;
    }

    private Div coverageCard(JsonNode evidence) {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("What was checked"));
        FlexLayout columns = new FlexLayout();
        columns.addClassName("cols");
        columns.add(coverageColumn("Checked", evidence.path("coverage").path("checked")), coverageColumn("Not checked", evidence.path("coverage").path("not_checked")),
                coverageColumn("Unavailable", evidence.path("coverage").path("unavailable")));
        card.add(columns);
        return card;
    }

    private static Div coverageColumn(String title, JsonNode items) {
        Div column = new Div();
        column.addClassName("col");
        column.add(new H3(title));
        UnorderedList list = new UnorderedList();
        items.forEach(item -> list.add(new ListItem(item.asText())));
        if (items.isEmpty()) {
            list.add(new ListItem("nothing"));
        }
        column.add(list);
        return column;
    }

    private Div diagramCard() {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("Change diagram"));
        diagram = new MermaidDiagram();
        drawer = new Div();
        drawer.addClassName("muted");
        drawer.add(new Paragraph("Select a part of the diagram to see its details here."));
        drawer.getElement().setAttribute("aria-live", "polite");
        Select<String> mode = new Select<>();
        mode.setLabel("View");
        mode.setItems("DIFF", "BEFORE", "AFTER");
        mode.setValue("DIFF");
        mode.setItemLabelGenerator(item -> item.charAt(0) + item.substring(1).toLowerCase());
        mode.addValueChangeListener(event -> loadDiagram(event.getValue()));
        Button zoomIn = new Button("Zoom in", event -> diagram.zoom(0.2));
        Button zoomOut = new Button("Zoom out", event -> diagram.zoom(-0.2));
        Button fit = new Button("Fit", event -> diagram.fit());
        Button svg = new Button("Download SVG", event -> diagram.fetchSvg(this::downloadSvg));
        Anchor markdown = new Anchor(new StreamResource("sentry-" + job.id() + ".md", () -> new ByteArrayInputStream(markdownBytes())), "Download Markdown");
        markdown.getElement().setAttribute("download", true);
        markdown.addClassName("btn-link");
        FlexLayout controls = new FlexLayout(mode, zoomIn, zoomOut, fit, svg, markdown);
        controls.addClassName("toolbar");
        diagram.onNodeSelected(this::describe);
        card.add(controls, diagram, drawer);
        loadDiagram("DIFF");
        Details limits = new Details("Limits of this diagram");
        card.add(limits);
        limitsInto(limits);
        return card;
    }

    private void limitsInto(Details limits) {
        if (diagramJson != null) {
            UnorderedList list = new UnorderedList();
            diagramJson.path("diagram").path("limitations").forEach(item -> list.add(new ListItem(item.asText())));
            limits.add(list);
        }
    }

    private void loadDiagram(String mode) {
        try {
            diagramJson = Json.parse(Json.text(insight.diagram(job.id(), MermaidRenderer.Mode.valueOf(mode))));
            diagram.render(diagramJson.path("mermaid").asText());
        } catch (IOException | JobService.Rejected problem) {
            Notification.show("The diagram could not be built: " + problem.getMessage());
        }
    }

    private void describe(String mermaidId) {
        if (diagramJson == null) {
            return;
        }
        String nodeId = diagramJson.path("node_ids").path(mermaidId).asText("");
        drawer.removeAll();
        for (JsonNode node : diagramJson.path("diagram").path("nodes")) {
            if (node.path("id").asText().equals(nodeId)) {
                drawer.add(new H3(node.path("label").asText()), new Paragraph("Kind: " + node.path("type").asText().toLowerCase() + ", change: " + node.path("change").asText().toLowerCase()
                        + (node.path("inferred").asBoolean() ? " (inferred, not measured)" : "")));
                if (node.path("evidence_ids").isEmpty()) {
                    drawer.add(new Paragraph("This part comes straight from the measured differences in the evidence file."));
                } else {
                    drawer.add(new Paragraph("Evidence files: " + node.path("evidence_ids").toString()));
                }
                return;
            }
        }
    }

    private void downloadSvg(String svg) {
        if (svg == null || svg.isBlank()) {
            Notification.show("There is no drawn diagram to download yet.");
            return;
        }
        UI.getCurrent().getPage().executeJs("const blob = new Blob([$0], {type: 'image/svg+xml'}); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); "
                + "link.download = $1; link.click(); setTimeout(() => URL.revokeObjectURL(link.href), 1000);", svg, "sentry-" + job.id() + ".svg");
    }

    private byte[] markdownBytes() {
        try {
            return insight.markdown(job.id(), MermaidRenderer.Mode.DIFF, false).getBytes(StandardCharsets.UTF_8);
        } catch (IOException | JobService.Rejected problem) {
            return ("The report could not be built: " + problem.getMessage()).getBytes(StandardCharsets.UTF_8);
        }
    }

    private Div explanationCard() {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("Plain-language explanation"));
        Div output = new Div();
        Button start = new Button("Explain this result", event -> openExplainDialog(output));
        card.add(new Paragraph("Optional. You choose what is shared and see where it goes before anything is sent. A model can never change the verdict."), start, output);
        return card;
    }

    private void openExplainDialog(Div output) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Explain this result");
        dialog.setWidth("38rem");
        RadioButtonGroup<String> mode = new RadioButtonGroup<>("What to include");
        mode.setItems("Report only", "Report plus selected project files");
        mode.setValue("Report only");
        CheckboxGroup<String> files = new CheckboxGroup<>();
        files.setLabel("Files that import the changed library (at most " + InsightService.MAX_FILES + ")");
        try {
            files.setItems(insight.candidateFiles(job.id()));
        } catch (IOException | JobService.Rejected problem) {
            files.setItems(List.of());
        }
        files.setVisible(false);
        Div preview = new Div();
        Button confirm = new Button();
        confirm.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Runnable refresh = () -> {
            preview.removeAll();
            List<String> chosen = mode.getValue().startsWith("Report plus") ? new ArrayList<>(files.getValue()) : List.of();
            try {
                JsonNode shown = Json.parse(Json.text(insight.preview(job.id(), chosen)));
                JsonNode destination = shown.path("destination");
                boolean sent = shown.path("sent").asBoolean();
                preview.add(new H3("What would be shared"));
                preview.add(new Paragraph(sent ? "Sent to: " + destination.path("host").asText() + " (model " + destination.path("model").asText() + ")" : shown.path("note").asText()));
                UnorderedList list = new UnorderedList();
                shown.path("items").forEach(item -> list.add(new ListItem(item.path("id").asText())));
                preview.add(list, new Paragraph(shown.path("total_bytes").asInt() + " bytes in total. " + (sent ? shown.path("note").asText() : "")));
                shown.path("file_texts").properties().forEach(entry -> {
                    Pre text = new Pre(entry.getValue().asText());
                    text.getStyle().set("max-height", "12rem").set("overflow", "auto");
                    preview.add(new Details("Exact text of " + entry.getKey() + " after secrets were removed", text));
                });
                confirm.setText(sent ? "Send this and explain" : "Build the explanation");
                confirm.setEnabled(true);
            } catch (IOException | JobService.Rejected problem) {
                preview.add(new Paragraph(problem.getMessage()));
                confirm.setEnabled(false);
            }
        };
        mode.addValueChangeListener(event -> {
            files.setVisible(event.getValue().startsWith("Report plus"));
            refresh.run();
        });
        files.addValueChangeListener(event -> refresh.run());
        confirm.addClickListener(event -> {
            List<String> chosen = mode.getValue().startsWith("Report plus") ? new ArrayList<>(files.getValue()) : List.of();
            dialog.close();
            showExplanation(output, chosen);
        });
        dialog.add(new VerticalLayout(mode, files, preview));
        dialog.getFooter().add(new Button("Cancel", event -> dialog.close()), confirm);
        refresh.run();
        dialog.open();
    }

    private void showExplanation(Div output, List<String> chosen) {
        output.removeAll();
        try {
            JsonNode answer = Json.parse(Json.text(insight.explain(job.id(), chosen)));
            if (answer.path("status").asText().equals("OK")) {
                output.add(new Paragraph(answer.path("labelled").asText() + " (" + answer.path("provider").asText() + ", " + answer.path("model").asText() + ")"),
                        new Paragraph(answer.path("summary").asText()));
                UnorderedList notes = new UnorderedList();
                answer.path("annotations").forEach(item -> notes.add(new ListItem(item.path("node_id").asText() + ": " + item.path("text").asText())));
                if (notes.getChildren().findAny().isPresent()) {
                    output.add(notes);
                }
                StringBuilder cited = new StringBuilder();
                answer.path("sources").forEach(item -> cited.append(cited.length() == 0 ? "" : ", ").append(item.asText()));
                output.add(new Paragraph("Evidence cited: " + cited));
                if (!answer.path("note").asText().isBlank()) {
                    output.add(new Paragraph(answer.path("note").asText()));
                }
            } else {
                output.add(new Paragraph(answer.path("note").asText()));
            }
        } catch (IOException | JobService.Rejected problem) {
            output.add(new Paragraph("The explanation could not be produced: " + problem.getMessage()));
        }
    }

    private Div riskCard(JsonNode change) {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("Risk estimate (advice only)"));
        Div output = new Div();
        Button estimate = new Button("Ask the risk service");
        estimate.addClickListener(event -> {
            output.removeAll();
            JsonNode answer = risk.predict(job.projectId(), change.path("package").asText(), change.path("from_version").asText(), change.path("to_version").asText());
            if (answer.path("status").asText().equals("OK")) {
                output.add(new Paragraph("Failure risk score " + String.format("%.2f", answer.path("risk_score").asDouble()) + " from model " + answer.path("model_version").asText()
                        + ". " + answer.path("supported_scope").asText()));
                output.add(new Paragraph("This is a ranking score, not a calibrated probability."));
            } else {
                output.add(new Paragraph("No estimate: " + answer.path("status").asText().replace('_', ' ').toLowerCase() + ". " + answer.path("reason").asText()));
            }
            output.add(new Paragraph(answer.path("advisory").asText()));
        });
        card.add(new Paragraph("The estimate uses version facts only and never changes the verdict above."), estimate, output);
        return card;
    }
}
