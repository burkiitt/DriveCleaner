package com.drivecleaner.scan.model;

import java.nio.file.Path;

public record ScanError(Path path, String operation, String message) {
}
