package org.upgradeguard.sentry.app.provider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.upgradeguard.sentry.app.config.SentryProperties;

@Component
public class ProviderRegistry {
    private final Map<String, ExecutionProvider> providers = new LinkedHashMap<>();

    @Autowired
    public ProviderRegistry(SentryProperties properties) {
        register(new LocalExecutionProvider(properties));
        register(new AzureVmExecutionProvider(new SshRemote(properties.azure()), properties.azure().enabled()));
    }

    public ProviderRegistry(List<ExecutionProvider> custom) {
        custom.forEach(this::register);
    }

    public final void register(ExecutionProvider provider) {
        providers.put(provider.id(), provider);
    }

    public Optional<ExecutionProvider> find(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    public List<String> ids() {
        return List.copyOf(providers.keySet());
    }
}
