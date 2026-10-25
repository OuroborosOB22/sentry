package org.upgradeguard.sentry.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PomEditorTest {
    private static final MavenCoordinate GUAVA = MavenCoordinate.parse("com.google.guava:guava");

    private static String pom(String dependencies, String extra) {
        return "<project>\n  <modelVersion>4.0.0</modelVersion>\n  <groupId>x</groupId>\n  <artifactId>y</artifactId>\n  <version>1</version>\n"
                + extra + "  <dependencies>\n" + dependencies + "  </dependencies>\n</project>\n";
    }

    private static String guava(String versionLine) {
        return "    <dependency>\n      <groupId>com.google.guava</groupId>\n      <artifactId>guava</artifactId>\n" + versionLine + "    </dependency>\n";
    }

    @Test
    void changesALiteralVersionAndKeepsEverythingElseByteForByte() throws Exception {
        String original = pom("    <!-- keep me -->\n" + guava("      <version>31.1-jre</version>\n")
                + "    <dependency>\n      <groupId>other</groupId>\n      <artifactId>lib</artifactId>\n      <version>1.0</version>\n    </dependency>\n", "");
        PomEditor.PinResult result = PomEditor.pin(original, GUAVA, "33.0.0-jre");
        assertEquals("31.1-jre", result.oldVersion());
        assertEquals(original.replace("<version>31.1-jre</version>", "<version>33.0.0-jre</version>"), result.pomText());
    }

    @Test
    void changesAVersionPropertyUsedByOnlyThisDependency() throws Exception {
        String original = pom(guava("      <version>${guava.version}</version>\n"), "  <properties>\n    <guava.version>31.1-jre</guava.version>\n  </properties>\n");
        PomEditor.PinResult result = PomEditor.pin(original, GUAVA, "33.0.0-jre");
        assertTrue(result.pomText().contains("<guava.version>33.0.0-jre</guava.version>"));
        assertEquals("property guava.version", result.place());
    }

    @Test
    void refusesAPropertyThatSeveralPlacesShare() {
        String original = pom(guava("      <version>${shared}</version>\n") + "    <dependency>\n      <groupId>o</groupId>\n      <artifactId>p</artifactId>\n      <version>${shared}</version>\n    </dependency>\n",
                "  <properties>\n    <shared>1.0</shared>\n  </properties>\n");
        UnsupportedPomException problem = assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(original, GUAVA, "2.0"));
        assertTrue(problem.getMessage().contains("shared by 2"));
    }

    @Test
    void changesTheVersionInDependencyManagementForAVersionlessDependency() throws Exception {
        String original = pom(guava(""), "  <dependencyManagement>\n    <dependencies>\n" + guava("      <version>31.1-jre</version>\n") + "    </dependencies>\n  </dependencyManagement>\n");
        PomEditor.PinResult result = PomEditor.pin(original, GUAVA, "33.0.0-jre");
        assertEquals("dependencyManagement", result.place());
        assertTrue(result.pomText().contains("<version>33.0.0-jre</version>"));
        assertFalse(result.pomText().contains("31.1-jre"));
    }

    @Test
    void refusesAVersionThatComesFromAParentOrBom() {
        UnsupportedPomException problem = assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(pom(guava(""), ""), GUAVA, "2.0"));
        assertTrue(problem.getMessage().contains("parent pom or a BOM"));
    }

    @Test
    void refusesAPropertyDefinedOutsideThisPom() {
        String original = pom(guava("      <version>${from.parent}</version>\n"), "");
        assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(original, GUAVA, "2.0"));
    }

    @Test
    void refusesMultiModuleProjects() {
        String original = pom(guava("      <version>1.0</version>\n"), "  <modules>\n    <module>a</module>\n  </modules>\n");
        assertTrue(assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(original, GUAVA, "2.0")).getMessage().contains("multi-module"));
    }

    @Test
    void refusesADependencyThatIsOnlyDeclaredInAProfile() {
        String original = pom("", "  <profiles>\n    <profile>\n      <id>p</id>\n      <dependencies>\n" + guava("      <version>1.0</version>\n") + "      </dependencies>\n    </profile>\n  </profiles>\n");
        assertTrue(assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(original, GUAVA, "2.0")).getMessage().contains("profile"));
    }

    @Test
    void refusesADependencyThatIsNotInThePom() {
        assertTrue(assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(pom("", ""), GUAVA, "2.0")).getMessage().contains("not a direct dependency"));
    }

    @Test
    void ignoresADependencyThatOnlyAppearsInAComment() throws Exception {
        String original = pom("    <!--\n" + guava("      <version>9.9</version>\n") + "    -->\n" + guava("      <version>31.1-jre</version>\n"), "");
        PomEditor.PinResult result = PomEditor.pin(original, GUAVA, "33.0.0-jre");
        assertTrue(result.pomText().contains("<version>9.9</version>"));
        assertTrue(result.pomText().contains("<version>33.0.0-jre</version>"));
    }

    @Test
    void refusesBrokenXmlAndDoctypes() {
        assertThrows(UnsupportedPomException.class, () -> PomEditor.pin("<project><dependencies>", GUAVA, "2.0"));
        String hostile = "<?xml version=\"1.0\"?><!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><project>&x;</project>";
        assertThrows(UnsupportedPomException.class, () -> PomEditor.pin(hostile, GUAVA, "2.0"));
    }

    @Test
    void refusesHostileVersions() {
        String original = pom(guava("      <version>1.0</version>\n"), "");
        for (String bad : new String[] {"1.0</version><x>", "1.0; rm -rf /", "", "../1.0", "1 0"}) {
            assertThrows(IllegalArgumentException.class, () -> PomEditor.pin(original, GUAVA, bad), bad);
        }
    }

    @Test
    void refusesHostileCoordinates() {
        for (String bad : new String[] {"a", "a:b:c", "a b:c", "a:<b>", ":", "a:"}) {
            assertThrows(IllegalArgumentException.class, () -> MavenCoordinate.parse(bad), bad);
        }
    }
}
