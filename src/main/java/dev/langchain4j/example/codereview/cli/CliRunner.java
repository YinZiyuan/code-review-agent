package dev.langchain4j.example.codereview.cli;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@Component
@ConditionalOnProperty(name = "code-review.runtime", havingValue = "cli", matchIfMissing = true)
public class CliRunner implements ExitCodeGenerator {

    private final RootCommand rootCommand;
    private final IFactory factory;
    private int exitCode = 0;

    public CliRunner(RootCommand rootCommand, IFactory factory) {
        this.rootCommand = rootCommand;
        this.factory = factory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run(ApplicationReadyEvent event) {
        String[] args = event.getArgs();
        exitCode = new CommandLine(rootCommand, factory).execute(args);
    }

    @Override public int getExitCode() { return exitCode; }
}
