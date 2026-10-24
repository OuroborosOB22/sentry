package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.router.RouterLink;

public class MainLayout extends AppLayout {
    private static final String APPLY_SAVED_THEME = "var saved = null; try { saved = localStorage.getItem('theme'); } catch (e) {} "
            + "if (saved !== 'light' && saved !== 'dark') { saved = 'dark'; } document.documentElement.setAttribute('theme', saved);";
    private static final String FLIP_THEME = "var next = document.documentElement.getAttribute('theme') === 'dark' ? 'light' : 'dark'; "
            + "document.documentElement.setAttribute('theme', next); try { localStorage.setItem('theme', next); } catch (e) {}";

    public MainLayout() {
        UiAccess.remember();
        Span mark = new Span();
        mark.addClassName("brand-mark");
        H1 title = new H1(mark, new Span("Sentry"));
        title.getStyle().set("font-size", "var(--lumo-font-size-xl)").set("margin", "0").set("display", "flex").set("align-items", "center");
        RouterLink projects = new RouterLink("Projects", ProjectsView.class);
        RouterLink settings = new RouterLink("Settings", SettingsView.class);
        Button theme = new Button(new Icon(VaadinIcon.ADJUST), event -> UI.getCurrent().getPage().executeJs(FLIP_THEME));
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
        UI.getCurrent().getPage().executeJs(APPLY_SAVED_THEME);
    }
}
