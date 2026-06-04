package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
public class SpotBugsAnalyzer implements StaticAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(SpotBugsAnalyzer.class);

    @FunctionalInterface
    public interface Runner {
        boolean run(Path classesDir, Path output) throws IOException;
    }

    private final Runner runner;
    private final SourceCompiler compiler;

    public SpotBugsAnalyzer(Runner runner, SourceCompiler compiler) {
        this.runner = runner;
        this.compiler = compiler;
    }

    @Override
    public String name() {
        return "spotbugs";
    }

    @Override
    public List<Violation> analyze(List<DiffParser.FileDiff> files) {
        return List.of();
    }

    public SpotBugsResult analyzeWithSource(List<DiffParser.FileDiff> files, Path sourceDir) {
        Optional<Path> classesDir = compiler.compile(sourceDir);
        if (classesDir.isEmpty()) {
            log.debug("SpotBugs skipped: source not compilable at {}", sourceDir);
            return SpotBugsResult.skipped();
        }
        try {
            Path output = Files.createTempFile("spotbugs-", ".xml");
            if (!runner.run(classesDir.get(), output)) {
                log.debug("SpotBugs runner reported skip");
                return SpotBugsResult.skipped();
            }
            return new SpotBugsResult(true, parseAndFilter(output, files));
        } catch (IOException e) {
            log.warn("SpotBugs I/O error: {}", e.toString());
            return SpotBugsResult.skipped();
        }
    }

    private List<Violation> parseAndFilter(Path xml, List<DiffParser.FileDiff> files) throws IOException {
        Set<String> changedKeys = new HashSet<>();
        for (DiffParser.FileDiff file : files) {
            for (DiffParser.AddedLine line : file.addedLines()) {
                changedKeys.add(file.path() + ":" + line.lineNumber());
            }
        }

        List<Violation> out = new ArrayList<>();
        try (var in = Files.newInputStream(xml)) {
            XMLEventReader reader = XMLInputFactory.newInstance().createXMLEventReader(in);
            String type = null;
            String priority = null;
            String message = null;
            String file = null;
            int line = -1;

            while (reader.hasNext()) {
                XMLEvent event = reader.nextEvent();
                if (event.isStartElement()) {
                    StartElement start = event.asStartElement();
                    String name = start.getName().getLocalPart();
                    switch (name) {
                        case "BugInstance" -> {
                            type = attr(start, "type");
                            priority = attr(start, "priority");
                            message = null;
                            file = null;
                            line = -1;
                        }
                        case "SourceLine" -> {
                            if (file == null) {
                                file = attr(start, "sourcepath");
                                line = parseInt(attr(start, "start"), -1);
                            }
                        }
                        case "LongMessage" -> {
                            XMLEvent text = reader.nextEvent();
                            if (text.isCharacters()) {
                                message = text.asCharacters().getData();
                            }
                        }
                        default -> {
                        }
                    }
                } else if (event.isEndElement()
                        && event.asEndElement().getName().getLocalPart().equals("BugInstance")
                        && file != null
                        && line > 0) {
                    String shortFile = file.substring(file.lastIndexOf('/') + 1);
                    if (changedKeys.contains(shortFile + ":" + line)) {
                        out.add(new Violation(mapSeverity(priority), shortFile, line, type,
                                message == null ? type : message));
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        return out;
    }

    private static String attr(StartElement start, String name) {
        var attr = start.getAttributeByName(new QName(name));
        return attr == null ? null : attr.getValue();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Severity mapSeverity(String priority) {
        return switch (priority == null ? "" : priority) {
            case "1" -> Severity.CRITICAL;
            case "2" -> Severity.WARNING;
            default -> Severity.SUGGESTION;
        };
    }
}
