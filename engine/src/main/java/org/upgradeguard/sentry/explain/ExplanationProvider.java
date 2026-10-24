package org.upgradeguard.sentry.explain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface ExplanationProvider {
    String id();

    String model();

    Result explain(JsonNode evidence, DiagramData diagram);

    record Annotation(String nodeId, String text) {
    }

    record Result(String status, String summary, List<Annotation> annotations, List<String> sources, String note) {
        public static final String OK = "OK";
        public static final String DISABLED = "DISABLED";
        public static final String UNAVAILABLE = "UNAVAILABLE";

        public static Result disabled() {
            return new Result(DISABLED, "", List.of(), List.of(), "Explanations are switched off. The deterministic report and diagram are complete without them.");
        }

        public static Result unavailable(String note) {
            return new Result(UNAVAILABLE, "", List.of(), List.of(), note);
        }
    }
}
