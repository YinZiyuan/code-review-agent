package dev.langchain4j.example.codereview.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(name = "eval", description = "Run evaluation suite (wired up in T21)")
public class EvalCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.err.println("eval command is implemented in T21");
        return 2;
    }
}
