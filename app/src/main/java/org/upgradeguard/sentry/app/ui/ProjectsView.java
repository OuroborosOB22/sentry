package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import java.util.List;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.jobs.ProjectService;
import org.upgradeguard.sentry.app.store.Rows;

@Route(value = "", layout = MainLayout.class)
@PageTitle("Projects | Sentry")
public class ProjectsView extends VerticalLayout {
    private final ProjectService projects;
    private final Grid<Rows.Project> grid = new Grid<>();
    private final Div empty = new Div();

    public ProjectsView(ProjectService projects) {
        this.projects = projects;
        addClassName("page");
        setPadding(false);
        H2 heading = new H2("Projects");
        Button register = new Button("Register a project", event -> openRegistration());
        register.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        HorizontalLayout top = new HorizontalLayout(heading, register);
        top.setWidthFull();
        top.setAlignItems(Alignment.CENTER);
        top.expand(heading);

        grid.addComponentColumn(project -> new RouterLink(project.name(), ProjectView.class, new com.vaadin.flow.router.RouteParameters("id", project.id()))).setHeader("Name").setAutoWidth(true);
        grid.addColumn(Rows.Project::ecosystem).setHeader("Kind").setAutoWidth(true);
        grid.addColumn(Rows.Project::path).setHeader("Folder").setFlexGrow(3);
        grid.setAllRowsVisible(true);
        grid.setAriaLabel("Registered projects");

        empty.addClassNames("card", "empty");
        empty.add(new H2("No projects yet"), new Paragraph("Register a folder that holds a pom.xml (Maven) or a requirements.txt (Python). "
                + "Sentry records when it worked, then tries dependency changes in separate copies and never touches the real folder."));
        add(top, grid, empty);
        refresh();
    }

    private void refresh() {
        List<Rows.Project> visible = projects.all().stream().filter(item -> UiAccess.allowed(item.id())).toList();
        grid.setItems(visible);
        grid.setVisible(!visible.isEmpty());
        empty.setVisible(visible.isEmpty());
    }

    private void openRegistration() {
        if (!UiAccess.administrator()) {
            Notification.show("Your access token cannot register projects.");
            return;
        }
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Register a project");
        TextField name = new TextField("Name");
        TextField path = new TextField("Folder on this machine");
        path.setWidth("28rem");
        path.setHelperText("It must sit inside one of the folders this installation may check.");
        name.setWidthFull();
        Div error = new Div();
        error.getStyle().set("color", "var(--lumo-error-text-color)");
        error.getElement().setAttribute("role", "alert");
        Button save = new Button("Register", event -> {
            try {
                projects.register(name.getValue(), path.getValue());
                dialog.close();
                refresh();
            } catch (JobService.Rejected problem) {
                error.setText(problem.getMessage());
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(name, path, error));
        dialog.getFooter().add(new Button("Cancel", event -> dialog.close()), save);
        dialog.open();
        name.focus();
    }
}
