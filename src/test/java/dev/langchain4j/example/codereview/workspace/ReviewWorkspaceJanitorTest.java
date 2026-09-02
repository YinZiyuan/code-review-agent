package dev.langchain4j.example.codereview.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewWorkspaceJanitorTest {

    private static final Instant NOW = Instant.parse("2026-09-02T08:00:00Z");

    @TempDir
    Path temporaryParent;

    @Test
    void restartJanitorDeletesOnlyOldExactlyMarkedDirectChildren() throws Exception {
        ReviewWorkspaceFactory originalProcess = new ReviewWorkspaceFactory(temporaryParent);
        ReviewWorkspace stale = originalProcess.create();
        Path staleRoot = stale.root();
        Files.writeString(stale.sourceDirectory().resolve("leftover.java"), "secret");
        Files.setLastModifiedTime(
                staleRoot.resolve(ReviewWorkspace.MARKER),
                FileTime.from(NOW.minus(Duration.ofDays(2))));

        ReviewWorkspace fresh = originalProcess.create();
        Path freshRoot = fresh.root();
        Files.setLastModifiedTime(
                freshRoot.resolve(ReviewWorkspace.MARKER), FileTime.from(NOW));

        Path unmarked = Files.createDirectory(
                temporaryParent.resolve(ReviewWorkspace.PREFIX + "unmarked"));
        Path wrongMarker = Files.createDirectory(
                temporaryParent.resolve(ReviewWorkspace.PREFIX + "wrong"));
        Files.writeString(wrongMarker.resolve(ReviewWorkspace.MARKER), "not-our-marker");
        Files.setLastModifiedTime(wrongMarker.resolve(ReviewWorkspace.MARKER),
                FileTime.from(NOW.minus(Duration.ofDays(2))));

        ReviewWorkspaceJanitor restarted = new ReviewWorkspaceJanitor(
                temporaryParent,
                Duration.ofHours(24),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(restarted.cleanStale()).isEqualTo(1);
        assertThat(staleRoot).doesNotExist();
        assertThat(freshRoot).exists();
        assertThat(unmarked).exists();
        assertThat(wrongMarker).exists();
    }

    @Test
    void neverFollowsAWorkspaceNamedSymlinkOutsideTheTemporaryParent() throws Exception {
        Path outside = Files.createTempDirectory("outside-review-workspace-");
        try {
            Files.writeString(outside.resolve(ReviewWorkspace.MARKER), ReviewWorkspace.MARKER_CONTENT);
            Files.writeString(outside.resolve("valuable.txt"), "keep");
            Path symlink = temporaryParent.resolve(ReviewWorkspace.PREFIX + "link");
            Files.createSymbolicLink(symlink, outside);

            ReviewWorkspaceJanitor janitor = new ReviewWorkspaceJanitor(
                    temporaryParent,
                    Duration.ofSeconds(1),
                    Clock.fixed(NOW, ZoneOffset.UTC));

            assertThat(janitor.cleanStale()).isZero();
            assertThat(outside.resolve("valuable.txt")).hasContent("keep");
            assertThat(symlink).exists();
        } finally {
            Files.deleteIfExists(outside.resolve(ReviewWorkspace.MARKER));
            Files.deleteIfExists(outside.resolve("valuable.txt"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aLaterProcessRecoversAMarkerLeftByCleanupFailure() throws Exception {
        ReviewWorkspace failed = new ReviewWorkspaceFactory(
                temporaryParent, root -> { throw new IOException("disk busy"); }).create();
        Path root = failed.root();
        assertThatThrownBy(failed::close).isInstanceOf(ReviewWorkspaceCleanupException.class);
        Files.setLastModifiedTime(root.resolve(ReviewWorkspace.MARKER),
                FileTime.from(NOW.minus(Duration.ofDays(2))));

        ReviewWorkspaceJanitor restarted = new ReviewWorkspaceJanitor(
                temporaryParent, Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(restarted.cleanStale()).isEqualTo(1);
        assertThat(root).doesNotExist();
    }
}
