package dev.langchain4j.example.codereview.server;

import java.util.Arrays;

public final class ApplicationMode {

    private static final String SERVER_MODE_TOKEN = "serve";

    private ApplicationMode() {
    }

    public static Selection select(String[] args) {
        String[] applicationArgs = args == null ? new String[0] : args.clone();
        boolean serverMode = applicationArgs.length > 0 && SERVER_MODE_TOKEN.equals(applicationArgs[0]);
        if (serverMode) {
            applicationArgs = Arrays.copyOfRange(applicationArgs, 1, applicationArgs.length);
        }
        return new Selection(serverMode, applicationArgs);
    }

    public record Selection(boolean serverMode, String[] applicationArgs) {

        public Selection {
            applicationArgs = applicationArgs.clone();
        }

        @Override
        public String[] applicationArgs() {
            return applicationArgs.clone();
        }
    }
}
