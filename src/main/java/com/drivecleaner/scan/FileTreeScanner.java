package com.drivecleaner.scan;

import com.drivecleaner.safety.ProtectedLocation;
import com.drivecleaner.safety.ProtectionPolicy;
import com.drivecleaner.scan.model.DirectoryRecord;
import com.drivecleaner.scan.model.FileRecord;
import com.drivecleaner.scan.model.FileSystemEntry;
import com.drivecleaner.scan.model.ScanError;
import com.drivecleaner.scan.model.TimestampInfo;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Consumer;

public final class FileTreeScanner {
    private static final int MAX_ERRORS = 1_000;
    private static final int PROGRESS_FILE_INTERVAL = 100;

    private final ProtectionPolicy protectionPolicy;

    public FileTreeScanner(ProtectionPolicy protectionPolicy) {
        this.protectionPolicy = protectionPolicy;
    }

    public ScanResult scan(ScanRequest request, CancellationToken cancellationToken,
                           Consumer<ScanProgress> progressConsumer) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        Objects.requireNonNull(progressConsumer, "progressConsumer");
        Path root = request.root().toAbsolutePath().normalize();
        List<ScanError> errors = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            errors.add(new ScanError(root, "validate", "The selected path is not a directory or drive"));
            return emptyResult(root, ScanStatus.INVALID_ROOT, errors);
        }

        ScanAccumulator accumulator = new ScanAccumulator(request, cancellationToken, progressConsumer, errors,
                protectionPolicy);
        try {
            Files.walkFileTree(root, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    accumulator.checkCancelled();
                    accumulator.enterDirectory(directory, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    accumulator.checkCancelled();
                    if (Files.isSymbolicLink(file)) {
                        accumulator.skip(file, "symbolic link");
                    } else if (attributes.isRegularFile()) {
                        accumulator.visitFile(file, attributes);
                    } else {
                        accumulator.skip(file, "unsupported filesystem entry");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    accumulator.skipFailure(file, exception);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
                    if (exception != null) {
                        accumulator.error(directory, "close directory", exception);
                    }
                    accumulator.leaveDirectory(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (ScanCancelledException ignored) {
            accumulator.cancelled = true;
        } catch (IOException exception) {
            accumulator.error(root, "scan", exception);
        }

        accumulator.publishProgress(root);
        ScanStatus status = cancellationToken.isCancelled() || accumulator.cancelled
                ? ScanStatus.CANCELLED
                : errors.isEmpty() ? ScanStatus.COMPLETED : ScanStatus.PARTIAL;
        return accumulator.result(root, status);
    }

    private static ScanResult emptyResult(Path root, ScanStatus status, List<ScanError> errors) {
        return new ScanResult(root, status, 0, 0, 0, 0, List.of(), List.of(), Map.of(), errors);
    }

    private static final class ScanAccumulator {
        private final ScanRequest request;
        private final CancellationToken cancellationToken;
        private final Consumer<ScanProgress> progressConsumer;
        private final List<ScanError> errors;
        private final ProtectionPolicy protectionPolicy;
        private final Deque<MutableDirectory> directoryStack = new ArrayDeque<>();
        private final Map<Path, MutableDirectory> directoriesByPath = new HashMap<>();
        private final PriorityQueue<FileRecord> largestFiles;
        private final PriorityQueue<DirectoryRecord> largestDirectories;
        private long fileCount;
        private long directoryCount;
        private long skippedCount;
        private long totalBytes;
        private boolean cancelled;
        private long lastProgressNanos = Long.MIN_VALUE;

        private ScanAccumulator(ScanRequest request, CancellationToken cancellationToken,
                                Consumer<ScanProgress> progressConsumer, List<ScanError> errors,
                                ProtectionPolicy protectionPolicy) {
            this.request = request;
            this.cancellationToken = cancellationToken;
            this.progressConsumer = progressConsumer;
            this.errors = errors;
            this.protectionPolicy = protectionPolicy;
            largestFiles = new PriorityQueue<>(Comparator.comparingLong(FileRecord::size));
            largestDirectories = new PriorityQueue<>(Comparator.comparingLong(DirectoryRecord::size));
        }

        private void checkCancelled() throws ScanCancelledException {
            if (cancellationToken.isCancelled()) {
                throw new ScanCancelledException();
            }
        }

        private void enterDirectory(Path directory, BasicFileAttributes attributes) {
            directoryCount++;
            ProtectedLocation protection = protectionPolicy.match(directory).orElse(null);
            Path normalized = directory.toAbsolutePath().normalize();
            MutableDirectory current = new MutableDirectory(normalized, TimestampInfo.from(attributes), protection);
            directoriesByPath.put(normalized, current);
            if (!directoryStack.isEmpty()) {
                directoryStack.peek().directoryCount++;
            }
            directoryStack.push(current);
            publishProgress(directory);
        }

        private void leaveDirectory(Path directory) {
            MutableDirectory current = directoryStack.pop();
            DirectoryRecord record = current.toRecord();
            offerLargestDirectory(record);
            publishProgress(directory);
        }

        private void visitFile(Path file, BasicFileAttributes attributes) {
            long size = attributes.size();
            fileCount++;
            totalBytes += size;
            for (MutableDirectory directory : directoryStack) {
                directory.size += size;
                directory.fileCount++;
            }

            ProtectedLocation protection = protectionPolicy.match(file).orElse(null);
            FileRecord record = new FileRecord(
                    file.toAbsolutePath().normalize(),
                    file.getFileName().toString(),
                    size,
                    FileSystemEntry.extensionFor(file),
                    TimestampInfo.from(attributes),
                    protection != null,
                    protection == null ? null : protection.reason());
            if (largestFiles.size() < request.largestFileLimit()) {
                largestFiles.offer(record);
            } else if (largestFiles.peek().size() < size) {
                largestFiles.poll();
                largestFiles.offer(record);
            }
            if (fileCount % PROGRESS_FILE_INTERVAL == 0) {
                publishProgress(file);
            }
        }

        private void skip(Path path, String reason) {
            skippedCount++;
            error(path, "skip", new IOException(reason));
        }

        private void skipFailure(Path path, IOException exception) {
            skippedCount++;
            error(path, "visit", exception);
        }

        private void error(Path path, String operation, IOException exception) {
            if (errors.size() < MAX_ERRORS) {
                errors.add(new ScanError(path, operation, exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage()));
            }
        }

        private void offerLargestDirectory(DirectoryRecord record) {
            if (largestDirectories.size() < request.largestDirectoryLimit()) {
                largestDirectories.offer(record);
            } else if (largestDirectories.peek().size() < record.size()) {
                largestDirectories.poll();
                largestDirectories.offer(record);
            }
        }

        private void publishProgress(Path currentPath) {
            long now = System.nanoTime();
            if (lastProgressNanos != Long.MIN_VALUE && now - lastProgressNanos < 100_000_000L) {
                return;
            }
            lastProgressNanos = now;
            progressConsumer.accept(new ScanProgress(fileCount, directoryCount, totalBytes, currentPath));
        }

        private ScanResult result(Path root, ScanStatus status) {
            List<FileRecord> files = largestFiles.stream()
                    .sorted(Comparator.comparingLong(FileRecord::size).reversed())
                    .toList();
            List<DirectoryRecord> directories = largestDirectories.stream()
                    .sorted(Comparator.comparingLong(DirectoryRecord::size).reversed())
                    .toList();
            Map<Path, DirectoryRecord> directoryIndex = directoriesByPath.entrySet().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().toRecord()));
            return new ScanResult(root, status, totalBytes, fileCount, directoryCount, skippedCount,
                    files, directories, directoryIndex, errors);
        }
    }

    private static final class MutableDirectory {
        private final Path path;
        private final TimestampInfo timestamps;
        private final ProtectedLocation protection;
        private long size;
        private long fileCount;
        private long directoryCount;

        private MutableDirectory(Path path, TimestampInfo timestamps, ProtectedLocation protection) {
            this.path = path;
            this.timestamps = timestamps;
            this.protection = protection;
        }

        private DirectoryRecord toRecord() {
            return new DirectoryRecord(path.toAbsolutePath().normalize(), size, fileCount, directoryCount,
                    timestamps, protection != null, protection == null ? null : protection.reason());
        }
    }

    private static final class ScanCancelledException extends IOException {
    }
}
