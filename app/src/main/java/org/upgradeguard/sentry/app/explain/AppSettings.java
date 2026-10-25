package org.upgradeguard.sentry.app.explain;

import java.net.URI;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.upgradeguard.sentry.app.config.SentryProperties;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.provider.ProviderRegistry;
import org.upgradeguard.sentry.app.store.SettingsRepository;

@Service
public class AppSettings {
    public static final Set<String> EXPLANATION_PROVIDERS = Set.of("DISABLED", "OPENAI_COMPATIBLE");

    private final SettingsRepository store;
    private final SentryProperties properties;
    private final ProviderRegistry providers;

    public AppSettings(SettingsRepository store, SentryProperties properties, ProviderRegistry providers) {
        this.store = store;
        this.properties = properties;
        this.providers = providers;
    }

    public String defaultExecution() {
        return store.get("execution.default").orElse(properties.defaultExecution());
    }

    public String explanationProvider() {
        return store.get("explanation.provider").orElse(properties.explanation().provider());
    }

    public String explanationModel() {
        return store.get("explanation.model").orElse(properties.explanation().model() == null ? "" : properties.explanation().model());
    }

    public String explanationBaseUrl() {
        return store.get("explanation.base_url").orElse(properties.explanation().baseUrl() == null ? "" : properties.explanation().baseUrl());
    }

    public boolean apiKeyPresent() {
        String name = properties.explanation().apiKeyEnvironmentVariable();
        return name != null && !name.isBlank() && System.getenv(name) != null && !System.getenv(name).isBlank();
    }

    public String apiKey() {
        String name = properties.explanation().apiKeyEnvironmentVariable();
        return name == null || name.isBlank() ? null : System.getenv(name);
    }

    public void update(String execution, String explanation, String model, String baseUrl) {
        if (execution != null && !execution.isBlank()) {
            if (providers.find(execution).isEmpty()) {
                throw new JobService.Rejected("execution must be one of " + providers.ids());
            }
            store.put("execution.default", execution);
        }
        if (explanation != null && !explanation.isBlank()) {
            if (!EXPLANATION_PROVIDERS.contains(explanation)) {
                throw new JobService.Rejected("explanation provider must be one of " + EXPLANATION_PROVIDERS);
            }
            store.put("explanation.provider", explanation);
        }
        if (model != null) {
            if (!model.matches("[A-Za-z0-9._:/-]{0,100}")) {
                throw new JobService.Rejected("the model name may only use letters, digits and . _ : / -");
            }
            store.put("explanation.model", model);
        }
        if (baseUrl != null) {
            store.put("explanation.base_url", checkedUrl(baseUrl));
        }
    }

    static String checkedUrl(String text) {
        if (text.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(text.trim());
            if (uri.getScheme() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https")) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return text.trim();
        } catch (IllegalArgumentException invalid) {
            throw new JobService.Rejected("the base URL must be a plain http or https address without credentials");
        }
    }

    public String host(String url) {
        try {
            return url == null || url.isBlank() ? "" : URI.create(url).getHost();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }
}
