package dev.langchain4j.example.codereview.reviewops;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "dev.langchain4j.example.codereview.reviewops",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ReviewOperationsArchitectureTest {

    @ArchTest
    static final ArchRule domain_has_no_framework_or_mechanism_dependencies = noClasses()
            .that().resideInAPackage("..reviewops.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "dev.langchain4j.model..",
                    "dev.langchain4j.service..",
                    "dev.langchain4j.data..",
                    "dev.langchain4j.rag..",
                    "dev.langchain4j.store..",
                    "..reviewops.application..",
                    "..reviewops.infrastructure..");
}
