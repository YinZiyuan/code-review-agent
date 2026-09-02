package dev.langchain4j.example.codereview.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewWorkspaceTest {

    @TempDir
    Path temporaryParent;

    @Test
    void oneMarkerBearingWorkspaceOwnsSourceArchiveClassesAndReports() throws Exception {
        ReviewWorkspace workspace = new ReviewWorkspaceFactory(temporaryParent).create();
        Path root = workspace.root();

        assertThat(root.getFileName().toString()).startsWith(ReviewWorkspace.PREFIX);
        assertThat(root.resolve(ReviewWorkspace.MARKER)).hasContent(ReviewWorkspace.MARKER_CONTENT);
        assertThat(workspace.sourceDirectory()).isDirectory();
        assertThat(workspace.archiveFile().getParent()).isEqualTo(root);
        assertThat(workspace.createClassesDirectory()).isDirectory().hasParent(root);
        assertThat(workspace.createReportFile()).isRegularFile().hasParent(root);

        workspace.close();
        workspace.close();

        assertThat(root).doesNotExist();
    }

    @Test
    void failedCleanupLeavesTheMarkerAsARestrictedDurableObligation() throws Exception {
        ReviewWorkspace workspace = new ReviewWorkspaceFactory(
                temporaryParent,
                root -> { throw new IOException("sensitive /arbitrary/path"); })
                .create();
        Path root = workspace.root();

        assertThatThrownBy(workspace::close)
                .isInstanceOf(ReviewWorkspaceCleanupException.class)
                .hasMessage("Could not remove review workspace")
                .hasNoCause();

        assertThat(root.resolve(ReviewWorkspace.MARKER)).isRegularFile();
    }

    @Test
    void analysisReusesPreparedWorkspaceButRemovesOnlyItsArtifacts() throws Exception {
        ReviewWorkspaceFactory factory = new ReviewWorkspaceFactory(temporaryParent);
        ReviewWorkspace owner = factory.create();
        Path classes;
        Path report;

        try (ReviewAnalysisWorkspace analysis = factory.analysisFor(owner.sourceDirectory())) {
            assertThat(analysis.root()).isEqualTo(owner.root());
            classes = analysis.createClassesDirectory();
            report = analysis.createReportFile();
            Files.writeString(classes.resolve("X.class"), "compiled");
        }

        assertThat(classes).doesNotExist();
        assertThat(report).doesNotExist();
        assertThat(owner.sourceDirectory()).exists();
        assertThat(owner.root().resolve(ReviewWorkspace.MARKER)).exists();
        owner.close();
    }

    @Test
    void analysisOfAnExternalSourceOwnsAndCleansAStandaloneWorkspace() throws Exception {
        ReviewWorkspaceFactory factory = new ReviewWorkspaceFactory(temporaryParent);
        Path externalSource = Files.createDirectory(temporaryParent.resolve("checkout"));
        Path analysisRoot;

        try (ReviewAnalysisWorkspace analysis = factory.analysisFor(externalSource)) {
            analysisRoot = analysis.root();
            assertThat(analysisRoot.resolve(ReviewWorkspace.MARKER)).exists();
            assertThat(analysisRoot).isNotEqualTo(externalSource);
            analysis.createClassesDirectory();
            analysis.createReportFile();
        }

        assertThat(analysisRoot).doesNotExist();
        assertThat(externalSource).exists();
    }

    @Test
    void simultaneousPipelineAndCleanupFailureRetainsOnlyASafeMarkerObligation()
            throws Exception {
        String arbitraryPath = "/private/repository/secret.java";
        ReviewWorkspace workspace = new ReviewWorkspaceFactory(
                temporaryParent,
                root -> { throw new IOException(arbitraryPath); })
                .create();
        RuntimeException pipelineFailure = new RuntimeException("pipeline failed");

        try {
            workspace.close();
        } catch (ReviewWorkspaceCleanupException cleanupFailure) {
            pipelineFailure.addSuppressed(cleanupFailure);
        }

        assertThat(workspace.root().resolve(ReviewWorkspace.MARKER)).exists();
        assertThat(pipelineFailure.getSuppressed()).singleElement()
                .isInstanceOf(ReviewWorkspaceCleanupException.class);
        assertThat(pipelineFailure.getSuppressed()[0])
                .hasMessage("Could not remove review workspace");
        assertThat(pipelineFailure.getSuppressed()[0].toString()).doesNotContain(arbitraryPath);
    }
}
