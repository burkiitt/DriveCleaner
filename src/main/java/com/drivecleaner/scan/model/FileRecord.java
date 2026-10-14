package com.drivecleaner.scan.model;

import java.nio.file.Path;

public record FileRecord(
        Path path,
        String name,
        long size,
        String extension,
        TimestampInfo timestamps,
        boolean protectedLocation,
        String protectionReason) {
}
