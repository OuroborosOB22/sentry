package org.upgradeguard.sentry.maven;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public final class Xml {
    private Xml() {
    }

    private static DocumentBuilder builder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    public static Document parse(String text) throws SAXException, IOException {
        try {
            return builder().parse(new InputSource(new StringReader(text)));
        } catch (ParserConfigurationException problem) {
            throw new IllegalStateException(problem);
        }
    }

    public static Document parse(InputStream stream) throws SAXException, IOException {
        try {
            return builder().parse(new InputSource(stream));
        } catch (ParserConfigurationException problem) {
            throw new IllegalStateException(problem);
        }
    }
}
