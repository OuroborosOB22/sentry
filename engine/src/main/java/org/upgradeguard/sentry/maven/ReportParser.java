package org.upgradeguard.sentry.maven;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class ReportParser {
    private ReportParser() {
    }

    public record Reports(List<TestResult> results, List<String> unreadable) {
    }

    public static Reports readFolders(Path... folders) throws IOException {
        List<TestResult> results = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> listing = Files.list(folder)) {
                files = listing.filter(path -> path.getFileName().toString().startsWith("TEST-")
                        && path.getFileName().toString().endsWith(".xml")).sorted().toList();
            }
            for (Path file : files) {
                try (InputStream stream = Files.newInputStream(file)) {
                    results.addAll(parse(stream));
                } catch (SAXException problem) {
                    unreadable.add(file.getFileName() + ": " + problem.getMessage());
                }
            }
        }
        return new Reports(results, unreadable);
    }

    public static List<TestResult> parse(InputStream stream) throws SAXException, IOException {
        List<TestResult> results = new ArrayList<>();
        NodeList cases = Xml.parse(stream).getElementsByTagName("testcase");
        for (int index = 0; index < cases.getLength(); index++) {
            Element testCase = (Element) cases.item(index);
            String identity = testCase.getAttribute("classname") + "::" + testCase.getAttribute("name");
            results.add(new TestResult(identity, outcomeOf(testCase)));
        }
        return results;
    }

    private static TestOutcome outcomeOf(Element testCase) {
        for (Node node = testCase.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                switch (element.getTagName()) {
                    case "failure":
                        return TestOutcome.FAIL;
                    case "error":
                        return TestOutcome.ERROR;
                    case "skipped":
                        return TestOutcome.SKIP;
                    default:
                        break;
                }
            }
        }
        return TestOutcome.PASS;
    }
}
