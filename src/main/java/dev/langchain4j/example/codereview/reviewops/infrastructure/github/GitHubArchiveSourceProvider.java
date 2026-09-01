package dev.langchain4j.example.codereview.reviewops.infrastructure.github;

import dev.langchain4j.example.codereview.reviewops.application.github.GitHubFailureException;
import dev.langchain4j.example.codereview.reviewops.application.github.PreparedReviewSource;
import dev.langchain4j.example.codereview.reviewops.application.github.ReviewSourceProvider;
import dev.langchain4j.example.codereview.reviewops.domain.PullRequestRevision;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GitHubArchiveSourceProvider implements ReviewSourceProvider {

    private static final int ZIP_CENTRAL_FILE_HEADER = 0x02014b50;
    private static final int ZIP_END_OF_CENTRAL_DIRECTORY = 0x06054b50;
    private static final int ZIP_END_MIN_SIZE = 22;
    private static final int ZIP_MAX_COMMENT_SIZE = 65_535;
    private static final int ZIP_CENTRAL_HEADER_SIZE = 46;
    private static final int UNIX_PLATFORM = 3;
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_SYMBOLIC_LINK = 0120000;
    private static final int MAX_ENTRY_NAME_BYTES = 4_096;
    private static final int MAX_ENTRY_DEPTH = 64;

    private final GitHubRestClient gitHub;
    private final Path temporaryParent;
    private final long maxDiffBytes;
    private final long maxArchiveBytes;
    private final long maxExpandedBytes;
    private final int maxEntries;

    public GitHubArchiveSourceProvider(
            GitHubRestClient gitHub,
            Path temporaryParent,
            long maxDiffBytes,
            long maxArchiveBytes,
            long maxExpandedBytes,
            int maxEntries
    ) {
        this.gitHub = Objects.requireNonNull(gitHub, "gitHub");
        this.temporaryParent = Objects.requireNonNull(temporaryParent, "temporaryParent")
                .toAbsolutePath().normalize();
        requireDownloadLimit(maxDiffBytes, "maxDiffBytes");
        requireDownloadLimit(maxArchiveBytes, "maxArchiveBytes");
        if (maxExpandedBytes <= 0) {
            throw new IllegalArgumentException("maxExpandedBytes must be positive");
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxDiffBytes = maxDiffBytes;
        this.maxArchiveBytes = maxArchiveBytes;
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxEntries = maxEntries;
    }

    @Override
    public PreparedReviewSource prepare(PullRequestRevision revision) {
        Objects.requireNonNull(revision, "revision");
        GitHubRestClient.validateFullCommitSha(revision.headSha());
        requireRealTemporaryParent();

        gitHub.requireExactPullRequestHead(revision);
        String diffPatch = gitHub.pullRequestDiff(revision, maxDiffBytes);
        byte[] archive = gitHub.repositoryArchive(revision, maxArchiveBytes);
        gitHub.requireExactPullRequestHead(revision);

        Path cleanupRoot = null;
        try {
            ArchiveMetadata metadata = inspectCentralDirectory(archive);
            cleanupRoot = Files.createTempDirectory(temporaryParent, "github-review-source-");
            Path sourceRoot = cleanupRoot.resolve("source");
            Files.createDirectory(sourceRoot);
            extractArchive(archive, metadata, sourceRoot);
            return new TemporaryPreparedReviewSource(diffPatch, sourceRoot, cleanupRoot);
        } catch (RuntimeException exception) {
            cleanupAfterFailure(cleanupRoot, exception);
            throw exception;
        } catch (IOException exception) {
            GitHubFailureException safeFailure =
                    transientFailure("Could not prepare GitHub review source");
            cleanupAfterFailure(cleanupRoot, safeFailure);
            throw safeFailure;
        }
    }

    private void requireRealTemporaryParent() {
        if (!Files.isDirectory(temporaryParent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(temporaryParent)) {
            throw new IllegalArgumentException("temporary source parent must be a real directory");
        }
    }

    private void extractArchive(byte[] archive, ArchiveMetadata metadata, Path sourceRoot)
            throws IOException {
        String commonRoot = null;
        int archiveEntries = 0;
        int extractedEntries = 0;
        long expandedBytes = 0;
        Set<Path> explicitPaths = new HashSet<>();

        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                archiveEntries++;
                String entryName = entry.getName();
                ValidatedEntry validated = validateEntry(entryName, commonRoot, sourceRoot);
                if (commonRoot == null) {
                    commonRoot = validated.commonRoot();
                }
                if (validated.relativePath() == null) {
                    zip.closeEntry();
                    continue;
                }
                extractedEntries++;
                if (extractedEntries > maxEntries) {
                    throw deterministicFailure("GitHub archive entry count limit exceeded");
                }
                if (!explicitPaths.add(validated.relativePath())) {
                    throw unsafeEntry();
                }

                Path target = sourceRoot.resolve(validated.relativePath()).normalize();
                if (!target.startsWith(sourceRoot)) {
                    throw unsafeEntry();
                }
                if (entry.isDirectory()) {
                    createDirectoriesWithoutFollowingLinks(sourceRoot, target);
                } else {
                    createDirectoriesWithoutFollowingLinks(sourceRoot, target.getParent());
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        throw unsafeEntry();
                    }
                    long declaredSize = entry.getSize();
                    if (declaredSize > maxExpandedBytes - expandedBytes) {
                        throw deterministicFailure(
                                "GitHub archive expanded size limit exceeded");
                    }
                    try (OutputStream output = Files.newOutputStream(
                            target,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS)) {
                        expandedBytes = copyEntryBounded(zip, output, expandedBytes);
                    }
                    if (Files.isSymbolicLink(target)) {
                        throw unsafeEntry();
                    }
                }
                zip.closeEntry();
            }
        }

        if (archiveEntries == 0 || commonRoot == null || archiveEntries != metadata.entryCount()) {
            throw deterministicFailure("GitHub archive is malformed");
        }
    }

    private long copyEntryBounded(ZipInputStream zip, OutputStream output, long expandedBytes)
            throws IOException {
        byte[] buffer = new byte[8_192];
        int read;
        long total = expandedBytes;
        while ((read = zip.read(buffer)) != -1) {
            if (read > maxExpandedBytes - total) {
                throw deterministicFailure("GitHub archive expanded size limit exceeded");
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return total;
    }

    private static ValidatedEntry validateEntry(
            String entryName, String expectedRoot, Path sourceRoot) {
        if (entryName == null
                || entryName.isBlank()
                || entryName.getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_NAME_BYTES
                || entryName.startsWith("/")
                || entryName.startsWith("\\")
                || entryName.indexOf('\\') >= 0
                || isWindowsAbsolute(entryName)
                || entryName.indexOf('\0') >= 0) {
            throw unsafeEntry();
        }

        int firstSlash = entryName.indexOf('/');
        if (firstSlash <= 0) {
            throw unsafeEntry();
        }
        String rootName = entryName.substring(0, firstSlash);
        if (".".equals(rootName) || "..".equals(rootName)) {
            throw unsafeEntry();
        }
        String commonRoot = entryName.substring(0, firstSlash + 1);
        if (expectedRoot != null && !expectedRoot.equals(commonRoot)) {
            throw unsafeEntry();
        }

        String relativeName = entryName.substring(firstSlash + 1);
        if (relativeName.isEmpty()) {
            if (!entryName.endsWith("/")) {
                throw unsafeEntry();
            }
            return new ValidatedEntry(commonRoot, null);
        }

        String[] segments = relativeName.split("/", -1);
        int nonEmptySegments = 0;
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            boolean trailingDirectorySeparator = index == segments.length - 1
                    && segment.isEmpty() && entryName.endsWith("/");
            if (trailingDirectorySeparator) {
                continue;
            }
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw unsafeEntry();
            }
            nonEmptySegments++;
        }
        if (nonEmptySegments == 0 || nonEmptySegments > MAX_ENTRY_DEPTH) {
            throw unsafeEntry();
        }

        try {
            Path relativePath = Path.of(relativeName).normalize();
            if (relativePath.isAbsolute()
                    || relativePath.startsWith("..")
                    || !sourceRoot.resolve(relativePath).normalize().startsWith(sourceRoot)) {
                throw unsafeEntry();
            }
            return new ValidatedEntry(commonRoot, relativePath);
        } catch (IllegalArgumentException exception) {
            throw unsafeEntry();
        }
    }

    private static boolean isWindowsAbsolute(String entryName) {
        return entryName.length() >= 3
                && Character.isLetter(entryName.charAt(0))
                && entryName.charAt(1) == ':'
                && (entryName.charAt(2) == '/' || entryName.charAt(2) == '\\');
    }

    private static void createDirectoriesWithoutFollowingLinks(Path root, Path directory)
            throws IOException {
        if (directory == null || directory.equals(root)) {
            return;
        }
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw unsafeEntry();
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static ArchiveMetadata inspectCentralDirectory(byte[] archive) {
        int endOffset = findEndOfCentralDirectory(archive);
        if (endOffset < 0) {
            throw deterministicFailure("GitHub archive is malformed");
        }
        int diskNumber = unsignedShort(archive, endOffset + 4);
        int centralDisk = unsignedShort(archive, endOffset + 6);
        int entriesOnDisk = unsignedShort(archive, endOffset + 8);
        int entryCount = unsignedShort(archive, endOffset + 10);
        long centralSize = unsignedInt(archive, endOffset + 12);
        long centralOffset = unsignedInt(archive, endOffset + 16);
        int commentLength = unsignedShort(archive, endOffset + 20);
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount
                || entryCount == 0xffff || centralSize == 0xffff_ffffL
                || centralOffset == 0xffff_ffffL
                || endOffset + ZIP_END_MIN_SIZE + commentLength != archive.length
                || centralOffset + centralSize != endOffset) {
            throw deterministicFailure("GitHub archive is malformed");
        }

        long cursor = centralOffset;
        long centralEnd = centralOffset + centralSize;
        for (int index = 0; index < entryCount; index++) {
            if (cursor < 0 || cursor + ZIP_CENTRAL_HEADER_SIZE > centralEnd
                    || littleEndianInt(archive, (int) cursor) != ZIP_CENTRAL_FILE_HEADER) {
                throw deterministicFailure("GitHub archive is malformed");
            }
            int header = (int) cursor;
            int versionMadeBy = unsignedShort(archive, header + 4);
            int nameLength = unsignedShort(archive, header + 28);
            int extraLength = unsignedShort(archive, header + 30);
            int entryCommentLength = unsignedShort(archive, header + 32);
            long externalAttributes = unsignedInt(archive, header + 38);
            long next = cursor + ZIP_CENTRAL_HEADER_SIZE
                    + nameLength + extraLength + entryCommentLength;
            if (nameLength == 0 || nameLength > MAX_ENTRY_NAME_BYTES || next > centralEnd) {
                throw deterministicFailure("GitHub archive is malformed");
            }
            decodeUtf8EntryName(archive, header + ZIP_CENTRAL_HEADER_SIZE, nameLength);
            int platform = versionMadeBy >>> 8;
            int unixMode = (int) (externalAttributes >>> 16);
            if (platform == UNIX_PLATFORM
                    && (unixMode & UNIX_FILE_TYPE_MASK) == UNIX_SYMBOLIC_LINK) {
                throw unsafeEntry();
            }
            cursor = next;
        }
        if (cursor != centralEnd) {
            throw deterministicFailure("GitHub archive is malformed");
        }
        return new ArchiveMetadata(entryCount);
    }

    private static void decodeUtf8EntryName(byte[] archive, int offset, int length) {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(archive, offset, length));
        } catch (CharacterCodingException exception) {
            throw deterministicFailure("GitHub archive is malformed");
        }
    }

    private static int findEndOfCentralDirectory(byte[] archive) {
        int firstPossible = Math.max(0, archive.length - ZIP_END_MIN_SIZE - ZIP_MAX_COMMENT_SIZE);
        for (int offset = archive.length - ZIP_END_MIN_SIZE; offset >= firstPossible; offset--) {
            if (littleEndianInt(archive, offset) == ZIP_END_OF_CENTRAL_DIRECTORY) {
                return offset;
            }
        }
        return -1;
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        if (offset < 0 || offset + 2 > bytes.length) {
            throw deterministicFailure("GitHub archive is malformed");
        }
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(littleEndianInt(bytes, offset));
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw deterministicFailure("GitHub archive is malformed");
        }
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static GitHubFailureException unsafeEntry() {
        return deterministicFailure("GitHub archive contains an unsafe entry");
    }

    private static void cleanupAfterFailure(Path cleanupRoot, RuntimeException failure) {
        if (cleanupRoot == null) {
            return;
        }
        try {
            deleteRecursively(cleanupRoot);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(transientFailure(
                    "Could not remove failed prepared review source"));
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void requireDownloadLimit(long limit, String name) {
        if (limit <= 0 || limit > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    name + " must be between 1 and Integer.MAX_VALUE bytes");
        }
    }

    private record ValidatedEntry(String commonRoot, Path relativePath) {
    }

    private record ArchiveMetadata(int entryCount) {
    }

    private static final class TemporaryPreparedReviewSource implements PreparedReviewSource {
        private final String diffPatch;
        private final Path sourceRoot;
        private final Path cleanupRoot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private TemporaryPreparedReviewSource(String diffPatch, Path sourceRoot, Path cleanupRoot) {
            this.diffPatch = Objects.requireNonNull(diffPatch, "diffPatch");
            this.sourceRoot = Objects.requireNonNull(sourceRoot, "sourceRoot");
            this.cleanupRoot = Objects.requireNonNull(cleanupRoot, "cleanupRoot");
        }

        @Override
        public String diffPatch() {
            return diffPatch;
        }

        @Override
        public Path sourceRoot() {
            return sourceRoot;
        }

        @Override
        public synchronized void close() {
            if (closed.get()) {
                return;
            }
            try {
                deleteRecursively(cleanupRoot);
                closed.set(true);
            } catch (IOException exception) {
                throw transientFailure("Could not remove prepared review source");
            }
        }

        @Override
        public String toString() {
            return "PreparedReviewSource[sourceRoot=" + sourceRoot + "]";
        }
    }

    private static GitHubFailureException deterministicFailure(String safeMessage) {
        return new GitHubFailureException(
                GitHubFailureException.Classification.DETERMINISTIC_INPUT, safeMessage);
    }

    private static GitHubFailureException transientFailure(String safeMessage) {
        return new GitHubFailureException(
                GitHubFailureException.Classification.TRANSIENT, safeMessage);
    }
}
