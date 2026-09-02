package dev.langchain4j.example.codereview.server;

/**
 * Supplies the non-secret, deterministic identity of the effective review work budget.
 * After stream B is merged, expose its budget as a bean whose body is
 * {@code return reviewWorkBudget::configurationHash;} to override the compatibility property.
 */
@FunctionalInterface
public interface ReviewWorkBudgetIdentityProvider {

    String workBudgetIdentity();
}
