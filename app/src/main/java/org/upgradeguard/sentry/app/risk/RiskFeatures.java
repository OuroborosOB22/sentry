package org.upgradeguard.sentry.app.risk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RiskFeatures {
    public static final String SCHEMA_VERSION = "1.0.0";
    private static final Pattern VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[.-]?(.*))?$");
    private static final List<String> PRERELEASE = List.of("alpha", "beta", "rc", "snapshot", "milestone", "preview", "cr");

    public record Result(Map<String, Object> features, List<String> missing) {
    }

    private record Parsed(int major, int minor, int patch, String suffix, boolean ok) {
    }

    private RiskFeatures() {
    }

    private static Parsed parse(String text) {
        Matcher match = VERSION.matcher(text.trim());
        if (!match.matches()) {
            return new Parsed(0, 0, 0, text, false);
        }
        return new Parsed(Integer.parseInt(match.group(1)), match.group(2) == null ? 0 : Integer.parseInt(match.group(2)),
                match.group(3) == null ? 0 : Integer.parseInt(match.group(3)), match.group(4) == null ? "" : match.group(4).toLowerCase(), true);
    }

    private static boolean prerelease(Parsed version) {
        return PRERELEASE.stream().anyMatch(version.suffix()::contains) || Pattern.compile("(^|[.-])m\\d+").matcher(version.suffix()).find();
    }

    private static String kind(Parsed before, Parsed after) {
        if (!before.ok() || !after.ok()) {
            return "unparsed";
        }
        if (after.major() != before.major()) {
            return "major";
        }
        return after.minor() != before.minor() ? "minor" : after.patch() != before.patch() ? "patch" : "other";
    }

    public static Result extract(String from, String to) {
        Parsed before = parse(from);
        Parsed after = parse(to);
        boolean ok = before.ok() && after.ok();
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("major_delta", ok ? after.major() - before.major() : null);
        features.put("minor_delta", ok ? after.minor() - before.minor() : null);
        features.put("patch_delta", ok ? after.patch() - before.patch() : null);
        features.put("from_prerelease", prerelease(before) ? 1 : 0);
        features.put("to_prerelease", prerelease(after) ? 1 : 0);
        features.put("is_downgrade", ok ? (compare(after, before) < 0 ? 1 : 0) : null);
        features.put("version_parsed", ok ? 1 : 0);
        features.put("change_kind", kind(before, after));
        List<String> missing = new ArrayList<>();
        features.forEach((name, value) -> {
            if (value == null) {
                missing.add(name);
            }
        });
        missing.addAll(List.of("scope", "section", "file_type"));
        return new Result(features, missing);
    }

    private static int compare(Parsed left, Parsed right) {
        return left.major() != right.major() ? Integer.compare(left.major(), right.major())
                : left.minor() != right.minor() ? Integer.compare(left.minor(), right.minor()) : Integer.compare(left.patch(), right.patch());
    }
}
