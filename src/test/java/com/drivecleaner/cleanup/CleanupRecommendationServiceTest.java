package com.drivecleaner.cleanup;

import com.drivecleaner.safety.ProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CleanupRecommendationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recommendsOnlyOldRegularFiles() throws IOException {
        Path candidateRoot = Files.createDirectory(temporaryDirectory.resolve("Temp"));
        Path oldFile = Files.writeString(candidateRoot.resolve("old.tmp"), "old");
        Files.writeString(candidateRoot.resolve("recent.tmp"), "recent");
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Files.setLastModifiedTime(oldFile, FileTime.from(now.minusSeconds(8 * 24 * 60 * 60L)));

        CleanupAnalysisResult result = service(candidateRoot, now).analyze(temporaryDirectory);

        assertEquals(List.of(oldFile.toAbsolutePath().normalize()),
                result.candidates().stream().map(CleanupCandidate::path).toList());
        assertEquals(CleanupCategory.TEMPORARY_FILE, result.candidates().get(0).category());
    }

    @Test
    void doesNotFollowSymbolicLinks() throws IOException {
        Path candidateRoot = Files.createDirectory(temporaryDirectory.resolve("Temp"));
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        Files.writeString(outside.resolve("not-a-candidate.tmp"), "data");
        Path link = candidateRoot.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
        }

        CleanupAnalysisResult result = service(candidateRoot, Instant.parse("2026-01-01T00:00:00Z"))
                .analyze(temporaryDirectory);

        assertTrue(result.candidates().isEmpty());
    }

    private CleanupRecommendationService service(Path candidateRoot, Instant now) {
        return new CleanupRecommendationService(
                new ProtectionPolicy(List.of()),
                Clock.fixed(now, ZoneOffset.UTC),
                new InstalledApplicationService(),
                List.of(candidateRoot));
    }
}
