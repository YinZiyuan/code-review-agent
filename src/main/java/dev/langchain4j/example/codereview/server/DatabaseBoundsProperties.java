package dev.langchain4j.example.codereview.server;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Finite operational bounds shared by Hikari, PostgreSQL JDBC, and Spring transactions. */
@ConfigurationProperties(prefix = "code-review.server.database")
public record DatabaseBoundsProperties(
        Integer maximumPoolSize,
        Integer minimumIdle,
        Duration acquisitionTimeout,
        Duration validationTimeout,
        Integer connectTimeoutSeconds,
        Integer socketTimeoutSeconds,
        Integer cancelTimeoutSeconds,
        Duration statementTimeout,
        Duration lockTimeout,
        Duration idleTransactionTimeout,
        Duration transactionTimeout) {

    private static final Duration HIKARI_MINIMUM_TIMEOUT = Duration.ofMillis(250);

    public DatabaseBoundsProperties {
        maximumPoolSize = defaultValue(maximumPoolSize, 8);
        minimumIdle = defaultValue(minimumIdle, 1);
        acquisitionTimeout = defaultValue(acquisitionTimeout, Duration.ofSeconds(2));
        validationTimeout = defaultValue(validationTimeout, Duration.ofSeconds(1));
        connectTimeoutSeconds = defaultValue(connectTimeoutSeconds, 5);
        socketTimeoutSeconds = defaultValue(socketTimeoutSeconds, 15);
        cancelTimeoutSeconds = defaultValue(cancelTimeoutSeconds, 2);
        statementTimeout = defaultValue(statementTimeout, Duration.ofSeconds(10));
        lockTimeout = defaultValue(lockTimeout, Duration.ofSeconds(2));
        idleTransactionTimeout = defaultValue(idleTransactionTimeout, Duration.ofSeconds(15));
        transactionTimeout = defaultValue(transactionTimeout, Duration.ofSeconds(10));

        requireRange(maximumPoolSize, 1, 64, "maximumPoolSize");
        requireRange(minimumIdle, 0, maximumPoolSize, "minimumIdle");
        requireDurationRange(
                acquisitionTimeout, HIKARI_MINIMUM_TIMEOUT, Duration.ofSeconds(30),
                "acquisitionTimeout");
        requireDurationRange(
                validationTimeout, HIKARI_MINIMUM_TIMEOUT, Duration.ofSeconds(5),
                "validationTimeout");
        if (validationTimeout.compareTo(acquisitionTimeout) >= 0) {
            throw new IllegalArgumentException("validationTimeout must be less than acquisitionTimeout");
        }
        requireRange(connectTimeoutSeconds, 1, 60, "connectTimeoutSeconds");
        requireRange(socketTimeoutSeconds, 1, 300, "socketTimeoutSeconds");
        requireRange(cancelTimeoutSeconds, 1, 30, "cancelTimeoutSeconds");
        requireDurationRange(statementTimeout, Duration.ofMillis(100), Duration.ofMinutes(10),
                "statementTimeout");
        requireDurationRange(lockTimeout, Duration.ofMillis(100), Duration.ofMinutes(1),
                "lockTimeout");
        requireDurationRange(
                idleTransactionTimeout, Duration.ofSeconds(1), Duration.ofMinutes(30),
                "idleTransactionTimeout");
        requireDurationRange(transactionTimeout, Duration.ofSeconds(1), Duration.ofMinutes(10),
                "transactionTimeout");
    }

    private static int defaultValue(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static Duration defaultValue(Duration value, Duration fallback) {
        return value == null ? fallback : value;
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static void requireDurationRange(
            Duration value,
            Duration minimum,
            Duration maximum,
            String name) {
        if (value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}
