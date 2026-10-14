package com.drivecleaner.scan;

import com.drivecleaner.safety.ProtectedLocation;
import com.drivecleaner.safety.ProtectionPolicy;
import com.drivecleaner.scan.model.FileSystemEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileTreeScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void calculatesNestedSizesAndLargestFiles() throws IOException {
        Path nested = Files.createDirectories(temporaryDirectory.resolve("nested"));
        Files.write(nested.resolve("small.txt"), new byte[4]);
        Files.write(temporaryDirectory.resolve("large.bin"), new byte[12]);

        ScanResult result = scanner(new ProtectionPolicy(List.of())).scan(
                new ScanRequest(temporaryDirectory, 10, 10), new CancellationToken(), progress -> { });

        assertEquals(16, result.totalBytes());
        assertEquals(2, result.fileCount());
        assertEquals(2, result.directoryCount());
        assertEquals("large.bin", result.largestFiles().get(0).name());
        assertEquals(16, result.largestDirectories().get(0).size());
        assertEquals("txt", result.largestFiles().get(result.largestFiles().size() - 1).extension());
        assertEquals(4, result.directoryIndex().get(nested.toAbsolutePath().normalize()).size());

        DirectoryListingResult listing = new DirectoryListingService(new ProtectionPolicy(List.of()))
                .list(temporaryDirectory, result, new CancellationToken());
        FileSystemEntry nestedEntry = listing.entries().stream()
                .filter(entry -> entry.name().equals("nested"))
                .findFirst()
                .orElseThrow();
        assertEquals(4, nestedEntry.size());
        assertTrue(nestedEntry.sizeCalculated());
    }

    @Test
    void doesNotFollowSymbolicLinks() throws IOException {
        Path target = Files.createDirectories(temporaryDirectory.resolveSibling("diskpilot-link-target"));
        try {
            Files.write(target.resolve("outside.txt"), new byte[20]);
            Path link = temporaryDirectory.resolve("linked-directory");
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | IOException exception) {
                assumeTrue(false, "Symbolic links unavailable: " + exception.getMessage());
            }

            ScanResult result = scanner(new ProtectionPolicy(List.of())).scan(
                    new ScanRequest(temporaryDirectory), new CancellationToken(), progress -> { });

            assertEquals(0, result.fileCount());
            assertTrue(result.skippedCount() > 0);
            assertFalse(result.largestFiles().stream().anyMatch(file -> file.name().equals("outside.txt")));
        } finally {
            Files.deleteIfExists(target.resolve("outside.txt"));
            Files.deleteIfExists(target);
        }
    }

    @Test
    void marksProtectedLocations() throws IOException {
        Path protectedPath = Files.createDirectories(temporaryDirectory.resolve("protected"));
        Files.write(protectedPath.resolve("config.dat"), new byte[3]);
        ProtectionPolicy policy = new ProtectionPolicy(List.of(
                new ProtectedLocation(protectedPath, "test protected location")));

        ScanResult result = scanner(policy).scan(new ScanRequest(temporaryDirectory),
                new CancellationToken(), progress -> { });

        assertTrue(result.largestFiles().stream().anyMatch(file ->
                file.protectedLocation() && file.protectionReason().equals("test protected location")));
        assertTrue(result.largestDirectories().stream().anyMatch(directory ->
                directory.path().equals(protectedPath) && directory.protectedLocation()));
    }

    @Test
    void supportsCancellationBeforeTraversal() {
        CancellationToken token = new CancellationToken();
        token.cancel();

        ScanResult result = scanner(new ProtectionPolicy(List.of())).scan(new ScanRequest(temporaryDirectory),
                token, progress -> { });

        assertEquals(ScanStatus.CANCELLED, result.status());
        assertEquals(0, result.fileCount());
    }

    @Test
    void supportsCancellationDuringTraversal() throws IOException {
        for (int index = 0; index < 250; index++) {
            Files.write(temporaryDirectory.resolve("file-" + index + ".dat"), new byte[1]);
        }
        CancellationToken token = new CancellationToken();

        ScanResult result = scanner(new ProtectionPolicy(List.of())).scan(new ScanRequest(temporaryDirectory),
                token, progress -> {
                    if (progress.filesVisited() >= 100) {
                        token.cancel();
                    }
                });

        assertEquals(ScanStatus.CANCELLED, result.status());
        assertTrue(result.fileCount() < 250);
    }

    @Test
    void rejectsAFileAsRoot() throws IOException {
        Path file = Files.writeString(temporaryDirectory.resolve("not-a-directory.txt"), "data");

        ScanResult result = scanner(new ProtectionPolicy(List.of())).scan(new ScanRequest(file),
                new CancellationToken(), progress -> { });

        assertEquals(ScanStatus.INVALID_ROOT, result.status());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void reportsUnavailableDirectoryAsError() throws IOException {
        ScanResult scanResult = scanner(new ProtectionPolicy(List.of())).scan(
                new ScanRequest(temporaryDirectory), new CancellationToken(), progress -> { });

        DirectoryListingResult listing = new DirectoryListingService(new ProtectionPolicy(List.of())).list(
                temporaryDirectory.resolve("missing"), scanResult, new CancellationToken());

        assertTrue(listing.errors().stream().anyMatch(error -> error.operation().equals("list")));
        assertTrue(listing.entries().isEmpty());
    }

    private static FileTreeScanner scanner(ProtectionPolicy policy) {
        return new FileTreeScanner(policy);
    }
}
