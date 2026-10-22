package com.drivecleaner.cleanup;

import com.drivecleaner.scan.model.ScanError;

import java.nio.file.Path;
import java.util.List;

public record CleanupAnalysisResult(
        Path scanRoot,
        List<CleanupCandidate> candidates,
        List<InstalledApplication> applications,
        List<ScanError> errors,
        boolean truncated) {

    public CleanupAnalysisResult {
        candidates = List.copyOf(candidates);
        applications = List.copyOf(applications);
        errors = List.copyOf(errors);
    }
}
