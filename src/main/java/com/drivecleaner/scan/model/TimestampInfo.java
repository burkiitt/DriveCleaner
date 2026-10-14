package com.drivecleaner.scan.model;

import java.time.Instant;
import java.nio.file.attribute.BasicFileAttributes;

public record TimestampInfo(
        Instant creationTime,
        Instant lastModifiedTime,
        Instant lastAccessTime) {

    public static TimestampInfo from(BasicFileAttributes attributes) {
        return new TimestampInfo(
                toInstant(attributes.creationTime()),
                toInstant(attributes.lastModifiedTime()),
                toInstant(attributes.lastAccessTime()));
    }

    private static Instant toInstant(java.nio.file.attribute.FileTime time) {
        try {
            return time.toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
