package dev.langchain4j.example.codereview.reviewops;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import dev.langchain4j.example.codereview.reviewops.application.SpringDependencyFixture;
import dev.langchain4j.example.codereview.reviewops.domain.EvaluatorDependencyFixture;
import dev.langchain4j.example.codereview.reviewops.domain.JdbcDependencyFixture;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewOperationsArchitectureProofTest {

    @Test
    void domainBoundaryRejectsAnEvaluatorDependency() {
        var fixtureClasses = new ClassFileImporter().importClasses(EvaluatorDependencyFixture.class);

        assertThatThrownBy(() -> ReviewOperationsArchitectureTest
                .domain_depends_only_on_jdk_and_its_own_package.check(fixtureClasses))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> ReviewOperationsArchitectureTest
                .production_review_operations_does_not_depend_on_evaluation.check(fixtureClasses))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void domainBoundaryRejectsAJdbcDependency() {
        var fixtureClasses = new ClassFileImporter().importClasses(JdbcDependencyFixture.class);

        assertThatThrownBy(() -> ReviewOperationsArchitectureTest
                .domain_depends_only_on_jdk_and_its_own_package.check(fixtureClasses))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void applicationBoundaryRejectsASpringRuntimeDependency() {
        var fixtureClasses = new ClassFileImporter().importClasses(SpringDependencyFixture.class);

        assertThatThrownBy(() -> ReviewOperationsArchitectureTest
                .application_does_not_depend_on_infrastructure_or_runtime_frameworks.check(fixtureClasses))
                .isInstanceOf(AssertionError.class);
    }
}
