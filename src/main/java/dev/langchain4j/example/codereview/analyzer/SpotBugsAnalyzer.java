package dev.langchain4j.example.codereview.analyzer;

import dev.langchain4j.example.codereview.infra.DiffParser;
import dev.langchain4j.example.codereview.model.Severity;
import dev.langchain4j.example.codereview.config.ReviewWorkBudget;
import dev.langchain4j.example.codereview.config.ReviewWorkBudgetProperties;
import dev.langchain4j.example.codereview.workspace.ReviewAnalysisWorkspace;
import dev.langchain4j.example.codereview.workspace.ReviewWorkspaceFactory;

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
import java.util.Set;

public class SpotBugsAnalyzer implements StaticAnalyzer {

    @FunctionalInterface
    public interface Runner {
        RunOutcome run(Path classesDir, Path output) throws IOException;
    }

    public enum RunOutcome {
        COMPLETED,
        TIMED_OUT,
        CANCELLED,
        FAILED,
        UNAVAILABLE
    }

    private final Runner runner;
    private final SourceCompiler compiler;
    private final ReviewWorkspaceFactory workspaceFactory;
    private final ReviewWorkBudget budget;

    public SpotBugsAnalyzer(Runner runner, SourceCompiler compiler) {
        this(runner, compiler,
                new ReviewWorkspaceFactory(Path.of(System.getProperty("java.io.tmpdir"))),
                defaults());
    }

    public SpotBugsAnalyzer(
            Runner runner, SourceCompiler compiler, ReviewWorkspaceFactory workspaceFactory) {
        this(runner, compiler, workspaceFactory, defaults());
    }

    public SpotBugsAnalyzer(
            Runner runner,
            SourceCompiler compiler,
            ReviewWorkspaceFactory workspaceFactory,
            ReviewWorkBudget budget) {
        this.runner = runner;
        this.compiler = compiler;
        this.workspaceFactory = workspaceFactory;
        this.budget = budget;
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
        try (ReviewAnalysisWorkspace workspace = workspaceFactory.analysisFor(sourceDir)) {
            Path classesDirectory = workspace.createClassesDirectory();
            CompilationResult compilation = compiler.compile(sourceDir, classesDirectory);
            if (!compilation.compiled()) {
                return SpotBugsResult.skipped(compilation.safeReason());
            }
            Path output = workspace.createReportFile();
            RunOutcome outcome = runner.run(classesDirectory, output);
            if (outcome != RunOutcome.COMPLETED) {
                return SpotBugsResult.skipped(safeReason(outcome));
            }
            long reportBytes = Files.size(output);
            if (reportBytes == 0 || reportBytes > budget.process().maxOutputBytes()) {
                return SpotBugsResult.skipped("analyzer output limit exceeded");
            }
            return new SpotBugsResult(true, parseAndFilter(output, files), "completed");
        } catch (IOException | RuntimeException exception) {
            return SpotBugsResult.skipped("analyzer unavailable");
        }
    }

    private static String safeReason(RunOutcome outcome) {
        return switch (outcome) {
            case TIMED_OUT -> "analyzer timed out";
            case CANCELLED -> "analyzer cancelled";
            case FAILED -> "analyzer failed";
            case UNAVAILABLE -> "analyzer unavailable";
            case COMPLETED -> "completed";
        };
    }

    private static ReviewWorkBudget defaults() {
        return new ReviewWorkBudgetProperties(null, null, null, null, null, null).toBudget();
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
