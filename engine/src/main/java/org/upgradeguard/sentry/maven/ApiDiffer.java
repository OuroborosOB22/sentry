package org.upgradeguard.sentry.maven;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import japicmp.cmp.JApiCmpArchive;
import japicmp.cmp.JarArchiveComparator;
import japicmp.cmp.JarArchiveComparatorOptions;
import japicmp.model.JApiClass;
import japicmp.model.JApiCompatibilityChange;
import japicmp.model.JApiMethod;
import japicmp.model.JApiField;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.upgradeguard.sentry.contract.Json;

public final class ApiDiffer {
    private ApiDiffer() {
    }

    public static Path jarPath(Path repository, MavenCoordinate coordinate, String version) {
        return repository.resolve(coordinate.groupId().replace('.', '/')).resolve(coordinate.artifactId()).resolve(version)
                .resolve(coordinate.artifactId() + "-" + version + ".jar");
    }

    private static String descriptor(JApiMethod method) {
        return method.getOldMethod().map(item -> item.getSignature()).orElse(method.getNewMethod().map(item -> item.getSignature()).orElse(""));
    }

    public static ObjectNode compare(Path oldJar, Path newJar, String oldVersion, String newVersion) {
        ObjectNode result = Json.object();
        result.put("old", oldVersion);
        result.put("new", newVersion);
        result.put("tool", "japicmp");
        result.put("limits", "Binary and source API differences of the library only. It does not prove that this project calls any changed member, and reflection is not analysed.");
        ArrayNode changes = result.putArray("incompatible_changes");
        if (!Files.isRegularFile(oldJar) || !Files.isRegularFile(newJar)) {
            result.put("available", false);
            result.put("reason", "the library jars were not found in the local Maven repository");
            return result;
        }
        JarArchiveComparatorOptions options = new JarArchiveComparatorOptions();
        options.getIgnoreMissingClasses().setIgnoreAllMissingClasses(true);
        List<JApiClass> classes;
        try {
            classes = new JarArchiveComparator(options).compare(new JApiCmpArchive(oldJar.toFile(), oldVersion), new JApiCmpArchive(newJar.toFile(), newVersion));
        } catch (RuntimeException failure) {
            result.put("available", false);
            result.put("reason", "the two library versions could not be compared: " + String.valueOf(failure.getMessage()).replaceAll("\\s+", " ").substring(0, Math.min(160, String.valueOf(failure.getMessage()).length())));
            return result;
        }
        int removedClasses = 0;
        int methodChanges = 0;
        int fieldChanges = 0;
        for (JApiClass apiClass : classes) {
            for (JApiCompatibilityChange change : apiClass.getCompatibilityChanges()) {
                changes.addObject().put("kind", change.getType().name()).put("member", apiClass.getFullyQualifiedName());
                removedClasses++;
            }
            for (JApiMethod method : apiClass.getMethods()) {
                for (JApiCompatibilityChange change : method.getCompatibilityChanges()) {
                    changes.addObject().put("kind", change.getType().name()).put("member", apiClass.getFullyQualifiedName() + "." + method.getName() + descriptor(method));
                    methodChanges++;
                }
            }
            for (JApiField field : apiClass.getFields()) {
                for (JApiCompatibilityChange change : field.getCompatibilityChanges()) {
                    changes.addObject().put("kind", change.getType().name()).put("member", apiClass.getFullyQualifiedName() + "." + field.getName());
                    fieldChanges++;
                }
            }
        }
        result.put("available", true);
        result.put("missing_classes_ignored", true);
        result.put("class_changes", removedClasses);
        result.put("method_changes", methodChanges);
        result.put("field_changes", fieldChanges);
        return result;
    }
}
