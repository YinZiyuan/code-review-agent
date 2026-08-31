package dev.langchain4j.example.codereview.analyzer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class SourceCompiler {

    private static final Logger log = LoggerFactory.getLogger(SourceCompiler.class);

    public Optional<Path> compile(Path sourceDir) {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            List<Path> javaFiles = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            if (javaFiles.isEmpty()) {
                return Optional.empty();
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                log.warn("No system Java compiler available.");
                return Optional.empty();
            }

            Path classesDir = Files.createTempDirectory("crv-classes-");
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classesDir.toFile()));
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(javaFiles);
                boolean ok = compiler.getTask(null, fm, null,
                        List.of("-nowarn", "-proc:none"), null, units).call();
                return ok ? Optional.of(classesDir) : Optional.empty();
            }
        } catch (IOException e) {
            log.warn("SourceCompiler I/O error: {}", e.toString());
            return Optional.empty();
        }
    }
}
