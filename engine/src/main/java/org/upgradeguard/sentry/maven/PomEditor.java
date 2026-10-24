package org.upgradeguard.sentry.maven;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class PomEditor {
    private static final Pattern PROPERTY_REFERENCE = Pattern.compile("^\\$\\{([^}]+)}$");
    private static final Pattern DEPENDENCY_BLOCK = Pattern.compile("<dependency>.*?</dependency>", Pattern.DOTALL);

    public record PinResult(String pomText, String oldVersion, String place) {
    }

    private PomEditor() {
    }

    public static PinResult pin(String pomText, MavenCoordinate target, String newVersion) throws UnsupportedPomException {
        Versions.require(newVersion);
        Document document;
        try {
            document = Xml.parse(pomText);
        } catch (SAXException | IOException problem) {
            throw new UnsupportedPomException("the pom.xml is not valid XML: " + problem.getMessage());
        }
        Element project = document.getDocumentElement();
        if (child(project, "modules") != null) {
            throw new UnsupportedPomException("multi-module projects are not supported yet");
        }
        if (child(project, "profiles") != null && mentions(child(project, "profiles"), target)) {
            throw new UnsupportedPomException("the dependency is declared inside a profile, which is not supported yet");
        }
        List<Element> direct = matching(child(project, "dependencies"), target);
        Element management = child(project, "dependencyManagement");
        List<Element> managed = matching(management == null ? null : child(management, "dependencies"), target);
        if (direct.size() > 1 || managed.size() > 1) {
            throw new UnsupportedPomException("the dependency is declared more than once");
        }
        if (direct.isEmpty() && managed.isEmpty()) {
            throw new UnsupportedPomException(target.text() + " is not a direct dependency of this pom.xml");
        }
        boolean fromDirect = !direct.isEmpty() && versionOf(direct.get(0)) != null;
        Element carrier = fromDirect ? direct.get(0) : (!managed.isEmpty() ? managed.get(0) : null);
        if (carrier == null || versionOf(carrier) == null) {
            throw new UnsupportedPomException("the version of " + target.text() + " is managed by a parent pom or a BOM, which is not supported yet");
        }
        String declared = versionOf(carrier);
        Matcher property = PROPERTY_REFERENCE.matcher(declared);
        if (property.matches()) {
            return pinProperty(pomText, project, property.group(1), newVersion);
        }
        return pinLiteral(pomText, target, declared, newVersion, fromDirect ? "dependencies" : "dependencyManagement");
    }

    private static PinResult pinProperty(String pomText, Element project, String name, String newVersion) throws UnsupportedPomException {
        Element properties = child(project, "properties");
        Element holder = properties == null ? null : child(properties, name);
        if (holder == null) {
            throw new UnsupportedPomException("the version property " + name + " is not defined in this pom.xml");
        }
        String masked = maskComments(pomText);
        int uses = count(masked, "${" + name + "}");
        if (uses > 1) {
            throw new UnsupportedPomException("the version property " + name + " is shared by " + uses + " places, so changing it would change more than one dependency");
        }
        String old = holder.getTextContent().trim();
        Pattern tag = Pattern.compile("(<" + Pattern.quote(name) + ">\\s*)" + Pattern.quote(old) + "(\\s*</" + Pattern.quote(name) + ">)");
        Matcher found = tag.matcher(masked);
        if (!found.find()) {
            throw new UnsupportedPomException("could not locate the property " + name + " in the text of the pom.xml");
        }
        String updated = pomText.substring(0, found.start()) + found.group(1) + newVersion + found.group(2) + pomText.substring(found.end());
        return new PinResult(updated, old, "property " + name);
    }

    private static PinResult pinLiteral(String pomText, MavenCoordinate target, String old, String newVersion, String place) throws UnsupportedPomException {
        String masked = maskComments(pomText);
        Matcher blocks = DEPENDENCY_BLOCK.matcher(masked);
        List<int[]> hits = new ArrayList<>();
        while (blocks.find()) {
            String block = blocks.group();
            if (tagValue(block, "groupId").equals(target.groupId()) && tagValue(block, "artifactId").equals(target.artifactId())
                    && !tagValue(block, "version").isEmpty()) {
                hits.add(new int[] {blocks.start(), blocks.end()});
            }
        }
        if (hits.size() != 1) {
            throw new UnsupportedPomException("could not find exactly one text block for " + target.text() + " in the pom.xml");
        }
        int start = hits.get(0)[0];
        int end = hits.get(0)[1];
        String block = pomText.substring(start, end);
        Matcher version = Pattern.compile("(<version>\\s*)" + Pattern.quote(old) + "(\\s*</version>)").matcher(block);
        if (!version.find()) {
            throw new UnsupportedPomException("could not locate the version text of " + target.text());
        }
        String newBlock = block.substring(0, version.start()) + version.group(1) + newVersion + version.group(2) + block.substring(version.end());
        return new PinResult(pomText.substring(0, start) + newBlock + pomText.substring(end), old, place);
    }

    private static String maskComments(String text) {
        Matcher comments = Pattern.compile("<!--.*?-->", Pattern.DOTALL).matcher(text);
        StringBuilder masked = new StringBuilder();
        while (comments.find()) {
            comments.appendReplacement(masked, Matcher.quoteReplacement(" ".repeat(comments.group().length())));
        }
        comments.appendTail(masked);
        return masked.toString();
    }

    private static int count(String text, String needle) {
        int total = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + needle.length())) {
            total++;
        }
        return total;
    }

    private static String tagValue(String block, String tag) {
        Matcher found = Pattern.compile("<" + tag + ">\\s*(.*?)\\s*</" + tag + ">", Pattern.DOTALL).matcher(block);
        return found.find() ? found.group(1) : "";
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static List<Element> matching(Element dependencies, MavenCoordinate target) {
        List<Element> found = new ArrayList<>();
        if (dependencies == null) {
            return found;
        }
        NodeList nodes = dependencies.getChildNodes();
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element && element.getTagName().equals("dependency")
                    && text(child(element, "groupId")).equals(target.groupId()) && text(child(element, "artifactId")).equals(target.artifactId())) {
                found.add(element);
            }
        }
        return found;
    }

    private static boolean mentions(Element profiles, MavenCoordinate target) {
        NodeList artifacts = profiles.getElementsByTagName("artifactId");
        for (int index = 0; index < artifacts.getLength(); index++) {
            Element artifact = (Element) artifacts.item(index);
            if (artifact.getTextContent().trim().equals(target.artifactId()) && artifact.getParentNode() instanceof Element parent
                    && text(child(parent, "groupId")).equals(target.groupId())) {
                return true;
            }
        }
        return false;
    }

    private static String versionOf(Element dependency) {
        Element version = child(dependency, "version");
        return version == null ? null : version.getTextContent().trim();
    }

    private static String text(Element element) {
        return element == null ? "" : element.getTextContent().trim();
    }
}
