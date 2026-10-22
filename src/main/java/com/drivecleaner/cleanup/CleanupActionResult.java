package com.drivecleaner.cleanup;

import com.drivecleaner.scan.model.ScanError;

import java.util.List;

public record CleanupActionResult(
        List<CleanupCandidate> movedToRecycleBin,
        List<ScanError> errors) {

    public CleanupActionResult {
        movedToRecycleBin = List.copyOf(movedToRecycleBin);
        errors = List.copyOf(errors);
    }
}
