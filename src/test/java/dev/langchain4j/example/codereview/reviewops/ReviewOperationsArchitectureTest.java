package dev.langchain4j.example.codereview.reviewops;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "dev.langchain4j.example.codereview.reviewops",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ReviewOperationsArchitectureTest {

    @ArchTest
    static final ArchRule domain_depends_only_on_jdk_and_its_own_package = classes()
            .that().resideInAPackage("..reviewops.domain..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java.lang..",
                    "java.nio.charset..",
                    "java.security..",
                    "java.time..",
                    "java.util..",
                    "..reviewops.domain..");

    @ArchTest
    static final ArchRule infrastructure_stays_inside_review_operations_and_framework_boundaries = classes()
            .that().resideInAPackage("..reviewops.infrastructure..")
            .should().onlyDependOnClassesThat().resideInAnyPackage(
                    "java..",
                    "..reviewops.infrastructure..",
                    "..reviewops.application..",
                    "..reviewops.domain..",
                    "com.fasterxml.jackson..",
                    "org.postgresql..",
                    "org.springframework.dao..",
                    "org.springframework.jdbc..",
                    "org.springframework.transaction..");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_persistence_frameworks = noClasses()
            .that().resideInAPackage("..reviewops.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..reviewops.infrastructure..",
                    "java.sql..",
                    "javax.sql..",
                    "com.fasterxml.jackson..",
                    "org.flywaydb..",
                    "org.springframework..",
                    "org.testcontainers..");

    @ArchTest
    static final ArchRule production_review_operations_does_not_depend_on_evaluation = noClasses()
            .that().resideInAPackage("..reviewops..")
            .should().dependOnClassesThat().resideInAPackage("..eval..");
}
