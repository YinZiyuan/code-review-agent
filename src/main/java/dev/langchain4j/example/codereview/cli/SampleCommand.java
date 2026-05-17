package dev.langchain4j.example.codereview.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Component
@Command(name = "sample", description = "Collect PR samples (W2)")
public class SampleCommand implements Callable<Integer> {
    @Override public Integer call() {
        System.err.println("sample command is implemented in W2");
        return 2;
    }
}
