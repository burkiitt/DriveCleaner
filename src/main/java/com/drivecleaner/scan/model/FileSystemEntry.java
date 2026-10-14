package com.drivecleaner.scan.model;

import java.nio.file.Path;
import java.util.Locale;

public record FileSystemEntry(
        Path path,
        String name,
        EntryKind kind,
        Long size,
        String extension,
        TimestampInfo timestamps,
        boolean sizeCalculated,
        boolean protectedLocation,
        String protectionReason) {

    public boolean directory() {
        return kind == EntryKind.DIRECTORY;
    }

    public static String extensionFor(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return "";
        }
        String name = fileName.toString();
        int separator = name.lastIndexOf('.');
        return separator > 0 && separator < name.length() - 1
                ? name.substring(separator + 1).toLowerCase(Locale.ROOT)
                : "";
    }
}
