package com.drivecleaner.scan;

import com.drivecleaner.scan.model.DirectoryRecord;
import com.drivecleaner.scan.model.FileRecord;
import com.drivecleaner.scan.model.ScanError;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Optional;

public record ScanResult(
        Path root,
        ScanStatus status,
        long totalBytes,
        long fileCount,
        long directoryCount,
        long skippedCount,
        List<FileRecord> largestFiles,
        List<DirectoryRecord> largestDirectories,
        Map<Path, DirectoryRecord> directoryIndex,
        List<ScanError> errors) {

    public ScanResult {
        largestFiles = List.copyOf(largestFiles);
        largestDirectories = List.copyOf(largestDirectories);
        directoryIndex = Map.copyOf(directoryIndex);
        errors = List.copyOf(errors);
    }

    public Optional<DirectoryRecord> directory(Path path) {
        return Optional.ofNullable(directoryIndex.get(path.toAbsolutePath().normalize()));
    }
}
