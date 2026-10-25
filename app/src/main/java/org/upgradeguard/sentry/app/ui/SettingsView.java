package org.upgradeguard.sentry.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.explain.AppSettings;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.provider.ExecutionProvider;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;

@Route(value = "settings", layout = MainLayout.class)
@PageTitle("Settings | Sentry")
public class SettingsView extends VerticalLayout {
    private final AppSettings settings;
    private final ProviderRegistry providers;
    private final SentryProperties properties;
    private final Div testResult = new Div();

    public SettingsView(AppSettings settings, ProviderRegistry providers, SentryProperties properties) {
        this.settings = settings;
        this.providers = providers;
        this.properties = properties;
        addClassName("page");
        setPadding(false);
        add(new H2("Settings"), new Paragraph("Where checks run and which model explains results are two separate choices. Changing one never changes the other, and never changes how a verdict is decided."));
        add(executionCard(), explanationCard());
    }

    private Div executionCard() {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("Where checks run"));
        Select<String> where = new Select<>();
        where.setLabel("Default execution location");
        where.setItems(providers.ids());
        where.setValue(settings.defaultExecution());
        Button save = new Button("Save", event -> {
            if (!UiAccess.administrator()) {
                Notification.show("Your access token cannot change settings.");
                return;
            }
            try {
                settings.update(where.getValue(), null, null, null);
                Notification.show("Saved.");
            } catch (JobService.Rejected problem) {
                Notification.show(problem.getMessage());
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button test = new Button("Test connection", event -> {
            testResult.removeAll();
            if (!UiAccess.administrator()) {
                testResult.add(new Paragraph("Your access token cannot test connections."));
                return;
            }
            ExecutionProvider.ProviderCheck check = providers.find(where.getValue()).map(ExecutionProvider::validate).orElse(null);
            if (check == null) {
                testResult.add(new Paragraph("No such provider."));
                return;
            }
            testResult.add(new Paragraph((check.usable() ? "Usable: " : "Not usable: ") + check.summary()));
            UnorderedList details = new UnorderedList();
            check.details().forEach(line -> details.add(new ListItem(line)));
            testResult.add(details);
        });
        card.add(where, new Paragraph("Local runs happen on this machine. The Azure VM option needs a configured host, user and key (see the README). "
                + "A successful connection test only shows the worker answers; it does not prove a full job works."), test, save, testResult);
        card.add(new Paragraph("Local execution is " + (properties.auth().tokens().isEmpty() || properties.allowLocalInHosted() ? "available." : "switched off while access tokens are configured.")));
        return card;
    }

    private Div explanationCard() {
        Div card = new Div();
        card.addClassName("card");
        card.add(new H3("Explanations"));
        Select<String> provider = new Select<>();
        provider.setLabel("Explanation provider");
        provider.setItems("DISABLED", "OPENAI_COMPATIBLE");
        provider.setValue(settings.explanationProvider());
        TextField url = new TextField("Base URL (any OpenAI-compatible endpoint)");
        url.setWidth("30rem");
        boolean administrator = UiAccess.administrator();
        url.setValue(administrator ? settings.explanationBaseUrl() : settings.host(settings.explanationBaseUrl()));
        url.setReadOnly(!administrator);
        TextField model = new TextField("Model or deployment name");
        model.setValue(settings.explanationModel());
        Paragraph key = new Paragraph(settings.apiKeyPresent() ? "An API key is set in the environment (its value is never shown)." : "No API key is set in the environment.");
        Button save = new Button("Save", event -> {
            if (!UiAccess.administrator()) {
                Notification.show("Your access token cannot change settings.");
                return;
            }
            try {
                settings.update(null, provider.getValue(), model.getValue(), url.getValue());
                Notification.show("Saved.");
            } catch (JobService.Rejected problem) {
                Notification.show(problem.getMessage(), 6000, Notification.Position.MIDDLE);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        card.add(provider, url, model, key, new Paragraph("Only measured facts are sent: the verdict, findings, dependency changes, failing test names and the coverage lists. Source code and logs are not sent. "
                + "Turn this off and every report and diagram still works."), save);
        return card;
    }
}
