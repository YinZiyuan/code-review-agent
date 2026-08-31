package dev.langchain4j.example.codereview.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryTest {

    @Test
    void exposesReviewFindingCategoriesInStableOrder() {
        assertThat(Category.values()).containsExactly(
                Category.SECURITY,
                Category.PERFORMANCE,
                Category.STABILITY,
                Category.CONCURRENCY,
                Category.TEST,
                Category.STYLE,
                Category.OTHER
        );
    }
}
