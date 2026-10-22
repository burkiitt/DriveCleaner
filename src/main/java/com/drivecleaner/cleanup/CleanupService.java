package com.drivecleaner.cleanup;

import com.drivecleaner.safety.ProtectionPolicy;
import com.drivecleaner.scan.model.ScanError;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CleanupService implements AutoCloseable {
    private final CleanupRecommendationService recommendationService;
    private final ProtectionPolicy protectionPolicy;
    private final ExecutorService executor;

    public CleanupService() {
        this(ProtectionPolicy.windowsDefaults());
    }

    public CleanupService(ProtectionPolicy protectionPolicy) {
        this.protectionPolicy = Objects.requireNonNull(protectionPolicy, "protectionPolicy");
        recommendationService = new CleanupRecommendationService(protectionPolicy);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "diskpilot-cleanup");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<CleanupAnalysisResult> analyzeAsync(Path scanRoot) {
        return CompletableFuture.supplyAsync(() -> recommendationService.analyze(scanRoot), executor);
    }

    public CompletableFuture<CleanupActionResult> moveToRecycleBinAsync(List<CleanupCandidate> candidates) {
        return CompletableFuture.supplyAsync(() -> moveToRecycleBin(candidates), executor);
    }

    public CompletableFuture<Void> openUninstallerAsync(InstalledApplication application) {
        return CompletableFuture.runAsync(() -> {
            String command = uninstallCommand(application.uninstallCommand());
            try {
                new ProcessBuilder("cmd.exe", "/d", "/s", "/c", command).start();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to start the application uninstaller", exception);
            }
        }, executor);
    }

    private CleanupActionResult moveToRecycleBin(List<CleanupCandidate> candidates) {
        List<CleanupCandidate> moved = new ArrayList<>();
        List<ScanError> errors = new ArrayList<>();
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            return new CleanupActionResult(List.of(), List.of(new ScanError(
                    null, "move to recycle bin", "Windows Recycle Bin is unavailable")));
        }
        for (CleanupCandidate candidate : candidates) {
            Path path = candidate.path().toAbsolutePath().normalize();
            if (protectionPolicy.match(path).isPresent()) {
                errors.add(new ScanError(path, "move to recycle bin", "Protected location"));
                continue;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                errors.add(new ScanError(path, "move to recycle bin", "File is no longer available"));
                continue;
            }
            try {
                if (Desktop.getDesktop().moveToTrash(path.toFile())) {
                    moved.add(candidate);
                } else {
                    errors.add(new ScanError(path, "move to recycle bin", "Windows could not move the file"));
                }
            } catch (RuntimeException exception) {
                errors.add(new ScanError(path, "move to recycle bin", message(exception)));
            }
        }
        return new CleanupActionResult(moved, errors);
    }

    private static String uninstallCommand(String command) {
        return command.replaceFirst("(?i)(msiexec(?:\\.exe)?\\s+)(/i)(\\s*\\{)", "$1/X$3");
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
