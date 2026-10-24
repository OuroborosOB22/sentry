package org.upgradeguard.sentry.explain;

import com.fasterxml.jackson.databind.JsonNode;

public final class DisabledExplanationProvider implements ExplanationProvider {
    @Override
    public String id() {
        return "DISABLED";
    }

    @Override
    public String model() {
        return "none";
    }

    @Override
    public Result explain(JsonNode evidence, DiagramData diagram) {
        return Result.disabled();
    }
}
