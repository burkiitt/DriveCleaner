package com.drivecleaner.scan;

import java.nio.file.Path;

public record ScanProgress(
        long filesVisited,
        long directoriesVisited,
        long bytesVisited,
        Path currentPath) {
}
