package com.drivecleaner.scan;

import com.drivecleaner.safety.ProtectedLocation;
import com.drivecleaner.safety.ProtectionPolicy;
import com.drivecleaner.scan.model.DirectoryRecord;
import com.drivecleaner.scan.model.EntryKind;
import com.drivecleaner.scan.model.FileSystemEntry;
import com.drivecleaner.scan.model.ScanError;
import com.drivecleaner.scan.model.TimestampInfo;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DirectoryListingService {
    private final ProtectionPolicy protectionPolicy;

    public DirectoryListingService(ProtectionPolicy protectionPolicy) {
        this.protectionPolicy = Objects.requireNonNull(protectionPolicy, "protectionPolicy");
    }

    public DirectoryListingResult list(Path directory, ScanResult scanResult,
                                       CancellationToken cancellationToken) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(scanResult, "scanResult");
        Objects.requireNonNull(cancellationToken, "cancellationToken");

        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        List<FileSystemEntry> entries = new ArrayList<>();
        List<ScanError> errors = new ArrayList<>();
        long skippedCount = 0;
        if (!Files.isDirectory(normalizedDirectory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedDirectory)) {
            errors.add(new ScanError(normalizedDirectory, "list", "Directory is unavailable"));
            return new DirectoryListingResult(normalizedDirectory, entries, 0, errors, false);
        }

        try (DirectoryStream<Path> children = Files.newDirectoryStream(normalizedDirectory)) {
            for (Path child : children) {
                if (cancellationToken.isCancelled()) {
                    return new DirectoryListingResult(normalizedDirectory, entries, skippedCount, errors, true);
                }
                if (Files.isSymbolicLink(child)) {
                    skippedCount++;
                    errors.add(new ScanError(child, "skip", "Symbolic link was not followed"));
                    continue;
                }
                try {
                    BasicFileAttributes attributes = Files.readAttributes(
                            child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isDirectory()) {
                        entries.add(directoryEntry(child, attributes, scanResult));
                    } else if (attributes.isRegularFile()) {
                        entries.add(fileEntry(child, attributes));
                    } else {
                        skippedCount++;
                        errors.add(new ScanError(child, "skip", "Unsupported filesystem entry"));
                    }
                } catch (IOException exception) {
                    skippedCount++;
                    errors.add(new ScanError(child, "read metadata", message(exception)));
                }
            }
        } catch (IOException | DirectoryIteratorException exception) {
            skippedCount++;
            errors.add(new ScanError(normalizedDirectory, "list", message(exception)));
        }
        return new DirectoryListingResult(normalizedDirectory, entries, skippedCount, errors, false);
    }

    private FileSystemEntry directoryEntry(Path path, BasicFileAttributes attributes, ScanResult scanResult) {
        Path normalized = path.toAbsolutePath().normalize();
        DirectoryRecord directory = scanResult.directoryIndex().get(normalized);
        ProtectedLocation protection = protectionPolicy.match(normalized).orElse(null);
        return new FileSystemEntry(
                normalized,
                nameOf(normalized),
                EntryKind.DIRECTORY,
                directory == null ? null : directory.size(),
                "",
                directory == null ? TimestampInfo.from(attributes) : directory.timestamps(),
                directory != null,
                protection != null,
                protection == null ? null : protection.reason());
    }

    private FileSystemEntry fileEntry(Path path, BasicFileAttributes attributes) {
        Path normalized = path.toAbsolutePath().normalize();
        ProtectedLocation protection = protectionPolicy.match(normalized).orElse(null);
        return new FileSystemEntry(
                normalized,
                nameOf(normalized),
                EntryKind.FILE,
                attributes.size(),
                FileSystemEntry.extensionFor(normalized),
                TimestampInfo.from(attributes),
                true,
                protection != null,
                protection == null ? null : protection.reason());
    }

    private static String nameOf(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static String message(Throwable exception) {
        Throwable cause = exception instanceof DirectoryIteratorException iteratorException
                ? iteratorException.getCause() : exception;
        if (cause == null) {
            cause = exception;
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
