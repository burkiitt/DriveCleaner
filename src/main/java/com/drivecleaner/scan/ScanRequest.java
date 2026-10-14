package com.drivecleaner.scan;

import java.nio.file.Path;
import java.util.Objects;

public record ScanRequest(Path root, int largestFileLimit, int largestDirectoryLimit) {
    public ScanRequest {
        Objects.requireNonNull(root, "root");
        if (largestFileLimit < 1 || largestDirectoryLimit < 1) {
            throw new IllegalArgumentException("Result limits must be positive");
        }
    }

    public ScanRequest(Path root) {
        this(root, 100, 100);
    }
}
