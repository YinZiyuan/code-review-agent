package dev.langchain4j.example.codereview.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

@Component
@Command(
        name = "code-review-agent",
        mixinStandardHelpOptions = true,
        version = "1.0.0",
        subcommands = {ReviewCommand.class, EvalCommand.class, SampleCommand.class}
)
public class RootCommand implements Runnable {
    @Override public void run() { /* show help via picocli when no subcommand */ }
}
