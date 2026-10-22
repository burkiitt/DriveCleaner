package com.drivecleaner.cleanup;

import java.nio.file.Path;
import java.time.Instant;

public record CleanupCandidate(
        Path path,
        String name,
        long size,
        CleanupCategory category,
        Instant lastModified,
        String recommendation) {
}
