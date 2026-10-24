package org.upgradeguard.sentry.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

class ParsersTest {
    @Test
    void readsEveryOutcomeFromASurefireReport() throws Exception {
        String xml = "<testsuite><testcase classname=\"a.B\" name=\"ok\"/><testcase classname=\"a.B\" name=\"bad\"><failure message=\"x\"/></testcase>"
                + "<testcase classname=\"a.B\" name=\"err\"><error message=\"x\"/></testcase><testcase classname=\"a.B\" name=\"skip\"><skipped/></testcase></testsuite>";
        List<TestResult> results = ReportParser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(new TestResult("a.B::ok", TestOutcome.PASS), new TestResult("a.B::bad", TestOutcome.FAIL),
                new TestResult("a.B::err", TestOutcome.ERROR), new TestResult("a.B::skip", TestOutcome.SKIP)), results);
    }

    @Test
    void refusesReportsThatTryToReadFiles() {
        String hostile = "<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><testsuite><testcase classname=\"&x;\" name=\"n\"/></testsuite>";
        org.junit.jupiter.api.Assertions.assertThrows(SAXException.class, () -> ReportParser.parse(new ByteArrayInputStream(hostile.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void reportsUnreadableFilesInsteadOfSilentlyIgnoringThem(@TempDir Path folder) throws Exception {
        Files.writeString(folder.resolve("TEST-a.xml"), "<testsuite><testcase classname=\"a\" name=\"ok\"/></testsuite>");
        Files.writeString(folder.resolve("TEST-b.xml"), "<testsuite><testcase");
        Files.writeString(folder.resolve("notes.xml"), "<ignored/>");
        ReportParser.Reports reports = ReportParser.readFolders(folder, folder.resolve("missing"));
        assertEquals(1, reports.results().size());
        assertEquals(1, reports.unreadable().size());
        assertTrue(reports.unreadable().get(0).startsWith("TEST-b.xml"));
    }

    @Test
    void readsMavenDependencyListOutput() {
        String text = "\nThe following files have been resolved:\n   org.junit.jupiter:junit-jupiter-api:jar:6.1.3:test -- module org.junit.jupiter.api\n"
                + "   com.google.guava:guava:jar:33.0.0-jre:compile\n   io.netty:netty-transport-native-epoll:jar:linux-x86_64:4.1.100:runtime\n   none\n";
        List<ResolvedDependency> found = DependencyListParser.parse(text);
        assertEquals(3, found.size());
        assertEquals("com.google.guava:guava", found.get(0).key());
        assertEquals("4.1.100", found.get(1).version());
        assertEquals("runtime", found.get(1).scope());
        assertEquals("6.1.3", found.get(2).version());
    }

    @Test
    void anEmptyDependencyListIsEmpty() {
        assertTrue(DependencyListParser.parse("\nThe following files have been resolved:\n   none\n").isEmpty());
    }
}
