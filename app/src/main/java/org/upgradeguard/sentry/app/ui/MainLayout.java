package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Pre;
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
        Image mark = new Image("logo.png", "");
        mark.addClassName("brand-logo");
        H1 title = new H1(mark, figlet(Figlet.SENTRY, "figlet-brand"), plain("Sentry"));
        title.addClassName("brand");
        Anchor home = new Anchor("/welcome/index.html");
        home.add(figlet(Figlet.HOME, "figlet-link"), plain("Home"));
        home.getElement().setAttribute("router-ignore", true);
        RouterLink projects = new RouterLink();
        projects.setRoute(ProjectsView.class);
        projects.add(figlet(Figlet.PROJECTS, "figlet-link"), plain("Projects"));
        RouterLink settings = new RouterLink();
        settings.setRoute(SettingsView.class);
        settings.add(figlet(Figlet.SETTINGS, "figlet-link"), plain("Settings"));
        home.addClassName("nav-link");
        projects.addClassName("nav-link");
        settings.addClassName("nav-link");
        Button theme = new Button(new Icon(VaadinIcon.ADJUST), event -> UI.getCurrent().getPage().executeJs(FLIP_THEME));
        theme.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        theme.getElement().setAttribute("aria-label", "Switch between light and dark mode");
        HorizontalLayout bar = new HorizontalLayout(title, home, projects, settings, theme);
        bar.setWidthFull();
        bar.setAlignItems(FlexComponent.Alignment.CENTER);
        bar.addClassName("topbar");
        bar.expand(title);
        addToNavbar(bar);
        UI.getCurrent().getPage().executeJs(APPLY_SAVED_THEME);
    }

    private static Pre figlet(String text, String kind) {
        Pre pre = new Pre(text);
        pre.addClassNames("figlet", kind);
        pre.getElement().setAttribute("aria-hidden", "true");
        return pre;
    }

    private static Span plain(String text) {
        Span span = new Span(text);
        span.addClassName("plain");
        return span;
    }
}
