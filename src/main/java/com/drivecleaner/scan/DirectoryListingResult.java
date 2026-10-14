package com.drivecleaner.scan;

import com.drivecleaner.scan.model.FileSystemEntry;
import com.drivecleaner.scan.model.ScanError;

import java.nio.file.Path;
import java.util.List;

public record DirectoryListingResult(
        Path directory,
        List<FileSystemEntry> entries,
        long skippedCount,
        List<ScanError> errors,
        boolean cancelled) {

    public DirectoryListingResult {
        entries = List.copyOf(entries);
        errors = List.copyOf(errors);
    }
}
