package dev.langchain4j.example.codereview.reviewops.application;

import dev.langchain4j.example.codereview.model.Citation;
import dev.langchain4j.example.codereview.reviewops.domain.CitationEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.CodeLocation;
import dev.langchain4j.example.codereview.reviewops.domain.FindingCategory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingContent;
import dev.langchain4j.example.codereview.reviewops.domain.FindingEvidence;
import dev.langchain4j.example.codereview.reviewops.domain.FindingFingerprintFactory;
import dev.langchain4j.example.codereview.reviewops.domain.FindingSeverity;

import java.util.List;
import java.util.Objects;

public final class ReviewFindingMapper {

    private final FindingFingerprintFactory fingerprintFactory;

    public ReviewFindingMapper() {
        this(new FindingFingerprintFactory());
    }

    public ReviewFindingMapper(FindingFingerprintFactory fingerprintFactory) {
        this.fingerprintFactory = Objects.requireNonNull(fingerprintFactory, "fingerprintFactory");
    }

    public dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding map(
            dev.langchain4j.example.codereview.model.ReviewFinding finding,
            FileDiffSet fileDiffs) {
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(fileDiffs, "fileDiffs");
        String file = FileDiffSet.normalizeFilePath(finding.file());
        if (finding.line() == null || finding.line() < 1) {
            throw new IllegalArgumentException("post-change line must be positive");
        }
        int postChangeLine = finding.line();
        CodeLocation location = new CodeLocation(
                file, postChangeLine, fileDiffs.containsAddedLine(file, postChangeLine));
        FindingContent content = new FindingContent(
                FindingSeverity.valueOf(Objects.requireNonNull(finding.severity(), "severity").name()),
                FindingCategory.valueOf(Objects.requireNonNull(finding.category(), "category").name()),
                finding.title(), finding.description(), finding.suggestion());
        FindingEvidence evidence = new FindingEvidence(
                finding.evidence(), mapCitations(finding.citations()), finding.source());
        return new dev.langchain4j.example.codereview.reviewops.domain.ReviewFinding(
                fingerprintFactory.create(location, content, evidence), location, content, evidence);
    }

    private static List<CitationEvidence> mapCitations(List<Citation> citations) {
        if (citations == null) {
            return List.of();
        }
        return citations.stream()
                .map(citation -> {
                    Objects.requireNonNull(citation, "citation");
                    return new CitationEvidence(citation.id(), citation.source(), citation.section());
                })
                .toList();
    }
}
