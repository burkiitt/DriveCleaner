package com.drivecleaner.scan;

import com.drivecleaner.safety.ProtectionPolicy;

import java.util.Objects;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ScanService implements AutoCloseable {
    private final ExecutorService executor;
    private final FileTreeScanner scanner;
    private final DirectoryListingService directoryListingService;

    public ScanService() {
        this(ProtectionPolicy.windowsDefaults());
    }

    public ScanService(ProtectionPolicy protectionPolicy) {
        scanner = new FileTreeScanner(Objects.requireNonNull(protectionPolicy, "protectionPolicy"));
        directoryListingService = new DirectoryListingService(protectionPolicy);
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "diskpilot-scanner");
            thread.setDaemon(true);
            return thread;
        });
    }

    public ScanHandle scanAsync(ScanRequest request, Consumer<ScanProgress> progressConsumer) {
        CancellationToken cancellationToken = new CancellationToken();
        CompletableFuture<ScanResult> future = CompletableFuture.supplyAsync(
                () -> scanner.scan(request, cancellationToken, progressConsumer), executor);
        return new ScanHandle(cancellationToken, future);
    }

    public CompletableFuture<DirectoryListingResult> listDirectoryAsync(Path directory, ScanResult scanResult) {
        CancellationToken cancellationToken = new CancellationToken();
        return CompletableFuture.supplyAsync(
                () -> directoryListingService.list(directory, scanResult, cancellationToken), executor);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public record ScanHandle(CancellationToken cancellationToken, CompletableFuture<ScanResult> future) {
    }
}
