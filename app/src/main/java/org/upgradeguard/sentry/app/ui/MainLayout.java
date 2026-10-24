package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.RouterLink;

public class MainLayout extends AppLayout {
    public MainLayout() {
        UiAccess.remember();
        H1 title = new H1("Sentry");
        title.getStyle().set("font-size", "var(--lumo-font-size-xl)").set("margin", "0");
        RouterLink projects = new RouterLink("Projects", ProjectsView.class);
        RouterLink settings = new RouterLink("Settings", SettingsView.class);
        Button theme = new Button("Dark mode", event -> toggleTheme(event.getSource()));
        theme.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        theme.getElement().setAttribute("aria-label", "Switch between light and dark mode");
        Span note = new Span("remembers when a project worked, tries changes in separate copies");
        note.addClassName("muted");
        HorizontalLayout bar = new HorizontalLayout(title, projects, settings, note, theme);
        bar.setWidthFull();
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.getStyle().set("padding", "0.5rem 1rem").set("flex-wrap", "wrap");
        bar.expand(note);
        addToNavbar(bar);
    }

    private static void toggleTheme(Button button) {
        boolean toDark = button.getText().equals("Dark mode");
        UI.getCurrent().getPage().executeJs("document.documentElement.setAttribute('theme', $0)", toDark ? "dark" : "light");
        button.setText(toDark ? "Light mode" : "Dark mode");
    }
}
