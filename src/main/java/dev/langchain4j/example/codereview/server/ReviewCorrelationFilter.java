package dev.langchain4j.example.codereview.server;

import dev.langchain4j.example.codereview.reviewops.domain.ReviewConfigurationSnapshot;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/** Adds one safe, correlated completion event around webhook processing. */
public final class ReviewCorrelationFilter extends OncePerRequestFilter {

    private final ReviewOperationLogger operations;
    private final ReviewConfigurationSnapshot configuration;

    public ReviewCorrelationFilter(
            ReviewOperationLogger operations,
            ReviewConfigurationSnapshot configuration) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        ReviewCorrelation correlation = ReviewCorrelation.webhook(
                request.getHeader("X-GitHub-Delivery"),
                configuration.pipelineVersion(),
                configuration.configurationVersion());
        try {
            filterChain.doFilter(request, response);
        } finally {
            operations.log(
                    correlation,
                    ReviewOperationLogger.Event.WEBHOOK,
                    response.getStatus() < 400
                            ? ReviewOperationLogger.Outcome.ACCEPTED
                            : ReviewOperationLogger.Outcome.REJECTED,
                    ReviewOperationLogger.SafeCode.NONE);
        }
    }
}
