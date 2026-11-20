package dev.depguard.dependency;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the direct dependencies from a pom.xml.
 *
 * <p>Deliberately simple: it does not download parent poms or BOMs. Dependencies whose version
 * comes from a parent/BOM are reported as "skipped" instead of guessed.
 */
@Component
public class PomParser {

    /** Matches a version that is exactly one property, e.g. "${spring.version}". */
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    public ParsedPom parse(String pomXml) {
        Element project = readXml(pomXml).getDocumentElement();
        if (!"project".equals(project.getNodeName())) {
            throw new PomParseException("Root element must be <project>");
        }

        Map<String, String> properties = readProperties(project);
        List<Dependency> dependencies = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        // Only <project><dependencies>, not <dependencyManagement> (those are not used unless declared).
        Element dependenciesElement = firstChild(project, "dependencies");
        List<Element> declared = dependenciesElement == null ? List.of() : children(dependenciesElement, "dependency");

        for (Element element : declared) {
            String groupId = text(element, "groupId");
            String artifactId = text(element, "artifactId");
            String rawVersion = text(element, "version");
            String scope = text(element, "scope");

            if (groupId == null || artifactId == null) {
                skipped.add("a dependency is missing groupId or artifactId");
                continue;
            }
            String name = groupId + ":" + artifactId;
            if (rawVersion == null) {
                skipped.add(name + " (version managed by a parent pom or BOM)");
                continue;
            }
            String version = resolve(rawVersion, properties);
            if (version == null) {
                skipped.add(name + " (could not resolve version " + rawVersion + ")");
                continue;
            }
            dependencies.add(new Dependency(groupId, artifactId, version, scope == null ? "compile" : scope));
        }
        return new ParsedPom(List.copyOf(dependencies), List.copyOf(skipped));
    }

    /**
     * Parses XML safely. A pom is user input, so DOCTYPE declarations are rejected outright: this
     * blocks XXE attacks (an XML file that makes the server read local files or call internal URLs).
     */
    private Document readXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler()); // throw on errors instead of printing to stderr
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PomParseException("Not a valid pom.xml: " + e.getMessage(), e);
        }
    }

    private Map<String, String> readProperties(Element project) {
        Map<String, String> properties = new HashMap<>();
        String projectVersion = text(project, "version");
        if (projectVersion != null) {
            properties.put("project.version", projectVersion);
        }
        Element propertiesElement = firstChild(project, "properties");
        if (propertiesElement != null) {
            NodeList nodes = propertiesElement.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element property) {
                    properties.put(property.getNodeName(), property.getTextContent().trim());
                }
            }
        }
        return properties;
    }

    /** "1.2.3" stays as is; "${x}" becomes the value of property x, or null if it is not defined. */
    private String resolve(String rawVersion, Map<String, String> properties) {
        Matcher matcher = PROPERTY.matcher(rawVersion);
        if (!matcher.matches()) {
            return rawVersion;
        }
        return properties.get(matcher.group(1));
    }

    // --- small DOM helpers ---

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element && element.getNodeName().equals(tag)) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element firstChild(Element parent, String tag) {
        List<Element> matches = children(parent, tag);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static String text(Element parent, String tag) {
        Element element = firstChild(parent, tag);
        if (element == null) {
            return null;
        }
        String value = element.getTextContent().trim();
        return value.isEmpty() ? null : value;
    }
}
