package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.upgradeguard.sentry.app.explain.AppSettings;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.ProjectRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.app.store.SnapshotRepository;
import org.upgradeguard.sentry.contract.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.upgradeguard.sentry.source.SourceManifest;

@Route(value = "project/:id", layout = MainLayout.class)
@PageTitle("Project | Sentry")
public class ProjectView extends VerticalLayout implements BeforeEnterObserver {
    private final ProjectRepository projects;
    private final SnapshotRepository snapshots;
    private final JobService jobs;
    private final ProviderRegistry providers;
    private final AppSettings settings;
    private Rows.Project project;
    private final ComboBox<String> execution = new ComboBox<>("Where to run");
    private final Grid<TimelineItem> timeline = new Grid<>();

    record TimelineItem(Instant when, String kind, String state, String detail, String jobId) {
    }

    public ProjectView(ProjectRepository projects, SnapshotRepository snapshots, JobService jobs, ProviderRegistry providers, AppSettings settings) {
        this.projects = projects;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.providers = providers;
        this.settings = settings;
        addClassName("page");
        setPadding(false);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String id = event.getRouteParameters().get("id").orElse("");
        Optional<Rows.Project> found = projects.find(id).filter(item -> UiAccess.allowed(item.id()));
        removeAll();
        if (found.isEmpty()) {
            add(new H2("Project not found"), new RouterLink("Back to projects", ProjectsView.class));
            return;
        }
        project = found.get();
        build();
    }

    private void build() {
        add(new RouterLink("All projects", ProjectsView.class));
        H2 heading = new H2(project.name());
        Span kind = new Span(project.ecosystem().equals("MAVEN") ? "Maven project" : "Python project");
        kind.addClassName("muted");
        add(heading, kind, identityCard(), actionsCard(), timelineCard());
        refreshTimeline();
    }

    private Div identityCard() {
        Div card = new Div();
        card.addClassName("card");
        List<Rows.Snapshot> all = snapshots.forProject(project.id());
        Optional<Rows.Snapshot> passing = all.stream().filter(item -> item.eligibility().equals("PASSING")).findFirst();
        card.add(new H3("Where it stands"));
        card.add(line("Folder", project.path()));
        card.add(line("Last eligible passing run", passing.map(item -> item.id() + " on " + item.createdAt()).orElse("none yet. Record a snapshot to start the history.")));
        card.add(line("Newest snapshot", all.isEmpty() ? "none" : all.get(0).id() + " (" + all.get(0).eligibility() + ", profile " + all.get(0).profileId() + ", source " + all.get(0).manifestSha256().substring(0, 12) + ")"));
        card.add(new Paragraph("A starting snapshot is not called passing until its whole test profile actually passed."));
        return card;
    }

    private static Div line(String label, String value) {
        Div row = new Div();
        Span name = new Span(label + ": ");
        name.getStyle().set("font-weight", "600");
        row.add(name, new Span(value));
        return row;
    }

    private Div actionsCard() {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("What do you want to do?"));
        execution.setItems(providers.ids());
        execution.setValue(settings.defaultExecution());
        execution.setAllowCustomValue(false);
        execution.setHelperText("Local runs stay on this machine. A remote host receives a copy of the project files (see the preview).");
        Button preview = new Button("Preview what a remote run would send", event -> showTransferPreview());
        preview.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        Button snapshot = new Button("Record a snapshot", event -> submit(new JobService.Submission("SNAPSHOT", null, null, null, null, execution.getValue())));
        Button compare = new Button("Compare", event -> openCompare());
        Button tryChange = new Button("Try a change", event -> openTryChange());
        tryChange.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button recovery = new Button("Test recovery", event -> openRecovery());
        recovery.setEnabled(project.ecosystem().equals("MAVEN"));
        if (!project.ecosystem().equals("MAVEN")) {
            recovery.getElement().setAttribute("title", "Recovery rehearsal is available for Maven projects.");
        }
        FlexLayout buttons = new FlexLayout(compare, tryChange, recovery, snapshot);
        buttons.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        buttons.getStyle().set("gap", "0.5rem");
        card.add(new HorizontalLayout(execution, preview), buttons);
        card.add(new Paragraph("Compare shows what changed since the last passing run. Try a change rehearses one dependency change in separate copies. "
                + "Test recovery rehearses an older version from a snapshot. None of them changes your real folder."));
        return card;
    }

    private Div timelineCard() {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("Timeline"));
        timeline.addColumn(item -> item.when().toString()).setHeader("When").setAutoWidth(true);
        timeline.addColumn(TimelineItem::kind).setHeader("What").setAutoWidth(true);
        timeline.addColumn(TimelineItem::state).setHeader("State").setAutoWidth(true);
        timeline.addColumn(TimelineItem::detail).setHeader("Details").setFlexGrow(2);
        timeline.addComponentColumn(item -> item.jobId() == null ? new Span() : new RouterLink("Open", RunView.class, new RouteParameters("id", item.jobId()))).setHeader("");
        timeline.setAllRowsVisible(true);
        timeline.setAriaLabel("History of snapshots and runs");
        card.add(timeline);
        return card;
    }

    private void refreshTimeline() {
        List<TimelineItem> items = new ArrayList<>();
        snapshots.forProject(project.id()).forEach(item -> items.add(new TimelineItem(item.createdAt(), "Snapshot", item.eligibility(), item.id() + ", profile " + item.profileId(), null)));
        for (Rows.Job job : jobs.forProject(project.id())) {
            String detail;
            try {
                JsonNode request = Json.parse(job.requestJson());
                detail = request.path("package").asText("") + (request.has("from") ? " " + request.path("from").asText() + " to " + request.path("to").asText() : "");
            } catch (IllegalArgumentException unreadable) {
                detail = "";
            }
            items.add(new TimelineItem(job.createdAt(), job.operation().replace('_', ' ').toLowerCase(), job.status() + (job.verdict() == null ? "" : ", " + job.verdict()), detail.trim(), job.id()));
        }
        items.sort(Comparator.comparing(TimelineItem::when).reversed());
        timeline.setItems(items);
    }

    private void submit(JobService.Submission submission) {
        try {
            String id = jobs.submit(project.id(), submission);
            UI.getCurrent().navigate(RunView.class, new RouteParameters("id", id));
        } catch (JobService.Rejected problem) {
            Notification.show(problem.getMessage(), 6000, Notification.Position.MIDDLE);
        }
    }

    private void openTryChange() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Try a change");
        TextField name = new TextField(project.ecosystem().equals("MAVEN") ? "Package (groupId:artifactId)" : "Package");
        TextField from = new TextField("Version in use today");
        TextField to = new TextField("Version to try");
        name.setWidthFull();
        dialog.add(new VerticalLayout(name, from, to, new Paragraph("The dependency is changed in a separate copy. Before and after are built and tested the same way, and only the difference is reported.")));
        Button go = new Button("Rehearse", event -> {
            dialog.close();
            submit(new JobService.Submission("REHEARSE_UPGRADE", name.getValue().trim(), from.getValue().trim(), to.getValue().trim(), null, execution.getValue()));
        });
        go.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", event -> dialog.close()), go);
        dialog.open();
        name.focus();
    }

    private void openRecovery() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Test recovery");
        ComboBox<Rows.Snapshot> snapshot = new ComboBox<>("Go back to the versions of this snapshot");
        snapshot.setItems(snapshots.forProject(project.id()));
        snapshot.setItemLabelGenerator(item -> item.id() + " (" + item.eligibility() + ")");
        snapshot.setWidthFull();
        TextField name = new TextField("Package to roll back (groupId:artifactId)");
        name.setWidthFull();
        dialog.add(new VerticalLayout(snapshot, name, new Paragraph("This only prepares and tests the older version in a copy. Your checkout is not reset.")));
        Button go = new Button("Rehearse recovery", event -> {
            if (snapshot.getValue() == null) {
                Notification.show("Choose a snapshot first.");
                return;
            }
            dialog.close();
            submit(new JobService.Submission("REHEARSE_RECOVERY", name.getValue().trim(), null, null, snapshot.getValue().id(), execution.getValue()));
        });
        go.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", event -> dialog.close()), go);
        dialog.open();
    }

    private void openCompare() {
        List<Rows.Snapshot> all = snapshots.forProject(project.id());
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Compare two snapshots");
        dialog.setWidth("40rem");
        if (all.size() < 2) {
            dialog.add(new Paragraph("A comparison needs two snapshots. Record a snapshot now and again after the next change."));
            dialog.getFooter().add(new Button("Close", event -> dialog.close()));
            dialog.open();
            return;
        }
        ComboBox<Rows.Snapshot> older = new ComboBox<>("Older");
        ComboBox<Rows.Snapshot> newer = new ComboBox<>("Newer");
        older.setItems(all);
        newer.setItems(all);
        older.setItemLabelGenerator(item -> item.id() + " (" + item.eligibility() + ")");
        newer.setItemLabelGenerator(older.getItemLabelGenerator());
        all.stream().filter(item -> item.eligibility().equals("PASSING")).reduce((first, second) -> second).ifPresent(older::setValue);
        newer.setValue(all.get(0));
        Div result = new Div();
        Button run = new Button("Compare", event -> {
            result.removeAll();
            try {
                JsonNode comparison = jobs.compare(project.id(), older.getValue() == null ? null : older.getValue().id(), newer.getValue() == null ? null : newer.getValue().id());
                result.add(ComparisonPanel.of(comparison));
            } catch (JobService.Rejected | IOException problem) {
                result.add(new Paragraph(problem.getMessage()));
            }
        });
        run.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(new HorizontalLayout(older, newer), run, result));
        dialog.getFooter().add(new Button("Close", event -> dialog.close()));
        dialog.open();
    }

    private void showTransferPreview() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("What a remote run would send");
        try {
            SourceManifest manifest = SourceManifest.of(java.nio.file.Path.of(project.path()));
            Div body = new Div();
            body.add(new Paragraph(manifest.fileHashes().size() + " files would be packed. These are never sent: " + String.join(", ", manifest.exclusionLabels())));
            Div list = new Div();
            list.getStyle().set("max-height", "16rem").set("overflow", "auto").set("font-family", "monospace");
            manifest.fileHashes().keySet().stream().limit(100).forEach(file -> list.add(new Div(new Span(file))));
            body.add(list);
            if (manifest.fileHashes().size() > 100) {
                body.add(new Paragraph("and " + (manifest.fileHashes().size() - 100) + " more"));
            }
            body.add(new Paragraph("Nothing is sent for local runs."));
            dialog.add(body);
        } catch (IOException problem) {
            dialog.add(new Paragraph("The files could not be listed."));
        }
        dialog.getFooter().add(new Button("Close", event -> dialog.close()));
        dialog.open();
    }
}
