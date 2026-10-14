package com.drivecleaner.scan.model;

import java.nio.file.Path;

public record DirectoryRecord(
        Path path,
        long size,
        long fileCount,
        long directoryCount,
        TimestampInfo timestamps,
        boolean protectedLocation,
        String protectionReason) {

    public String name() {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }
}
