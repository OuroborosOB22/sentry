package org.upgradeguard.sentry.app.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskFeaturesTest {
    private record Case(String from, String to, Object major, Object minor, Object patch, int fromPre, int toPre, Object downgrade, int parsed, String kind) {
    }

    @Test
    void matchesTheValuesTheTrainingCodeProduces() {
        List<Case> reference = List.of(
                new Case("1.2.3", "2.0.0", 1, -2, -3, 0, 0, 0, 1, "major"),
                new Case("31.1-jre", "33.0.0-jre", 2, -1, 0, 0, 0, 0, 1, "major"),
                new Case("2.0.0-beta1", "2.0.0", 0, 0, 0, 1, 0, 0, 1, "other"),
                new Case("latest", "2.0", null, null, null, 0, 0, null, 0, "unparsed"),
                new Case("3.4.1", "3.4.0", 0, 0, -1, 0, 0, 1, 1, "patch"),
                new Case("1.0", "1.0.1.RC2", 0, 0, 1, 0, 1, 0, 1, "patch"));
        for (Case expected : reference) {
            Map<String, Object> features = RiskFeatures.extract(expected.from(), expected.to()).features();
            String label = expected.from() + " to " + expected.to();
            assertEquals(expected.major(), features.get("major_delta"), label);
            assertEquals(expected.minor(), features.get("minor_delta"), label);
            assertEquals(expected.patch(), features.get("patch_delta"), label);
            assertEquals(expected.fromPre(), features.get("from_prerelease"), label);
            assertEquals(expected.toPre(), features.get("to_prerelease"), label);
            assertEquals(expected.downgrade(), features.get("is_downgrade"), label);
            assertEquals(expected.parsed(), features.get("version_parsed"), label);
            assertEquals(expected.kind(), features.get("change_kind"), label);
        }
    }

    @Test
    void emptyValuesAreListedAsMissingNotZero() {
        RiskFeatures.Result result = RiskFeatures.extract("latest", "2.0");
        assertNull(result.features().get("major_delta"));
        assertTrue(result.missing().containsAll(List.of("major_delta", "minor_delta", "patch_delta", "is_downgrade", "scope", "section", "file_type")));
    }

    @Test
    void contextThatTheHubDoesNotKnowIsListedAsMissing() {
        RiskFeatures.Result result = RiskFeatures.extract("1.0.0", "1.0.1");
        assertTrue(result.missing().containsAll(List.of("scope", "section", "file_type")));
    }
}
