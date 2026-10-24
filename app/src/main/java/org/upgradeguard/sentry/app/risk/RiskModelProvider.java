package org.upgradeguard.sentry.app.risk;

import com.fasterxml.jackson.databind.JsonNode;

public interface RiskModelProvider {
    boolean enabled();

    JsonNode predict(String projectId, String packageName, String fromVersion, String toVersion);
}
