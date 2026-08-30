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
    static final ArchRule production_review_operations_does_not_depend_on_evaluation = noClasses()
            .that().resideInAPackage("..reviewops..")
            .should().dependOnClassesThat().resideInAPackage("..eval..");
}
