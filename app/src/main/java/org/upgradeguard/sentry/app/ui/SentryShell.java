package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.server.AppShellSettings;
import com.vaadin.flow.theme.Theme;

@Theme("sentry")
public class SentryShell implements AppShellConfigurator {
    @Override
    public void configurePage(AppShellSettings settings) {
        settings.addFavIcon("icon", "logo-64.png", "64x64");
    }
}
