package com.drivecleaner.ui;

import com.drivecleaner.scan.ScanProgress;
import com.drivecleaner.scan.ScanRequest;
import com.drivecleaner.scan.ScanResult;
import com.drivecleaner.scan.ScanService;
import com.drivecleaner.scan.DirectoryListingResult;
import com.drivecleaner.scan.ScanStatus;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.nio.file.Path;
import java.util.Objects;

public final class ScanViewModel implements AutoCloseable {
    private final ScanService scanService;
    private final BooleanProperty scanning = new SimpleBooleanProperty();
    private final LongProperty filesVisited = new SimpleLongProperty();
    private final LongProperty directoriesVisited = new SimpleLongProperty();
    private final LongProperty bytesVisited = new SimpleLongProperty();
    private final StringProperty status = new SimpleStringProperty("Choose a directory or drive to scan.");
    private final ObjectProperty<ScanResult> result = new SimpleObjectProperty<>();
    private final ObjectProperty<DirectoryListingResult> listing = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> currentDirectory = new SimpleObjectProperty<>();
    private ScanService.ScanHandle activeScan;
    private long listingRequestId;

    public ScanViewModel(ScanService scanService) {
        this.scanService = Objects.requireNonNull(scanService, "scanService");
    }

    public void start(Path root) {
        if (scanning.get()) {
            return;
        }
        filesVisited.set(0);
        directoriesVisited.set(0);
        bytesVisited.set(0);
        result.set(null);
        listing.set(null);
        currentDirectory.set(null);
        listingRequestId++;
        scanning.set(true);
        status.set("Scanning " + root + "...");
        activeScan = scanService.scanAsync(new ScanRequest(root), progress -> onProgress(progress));
        activeScan.future().whenComplete((scanResult, throwable) -> runOnUiThread(() -> {
            scanning.set(false);
            activeScan = null;
            if (throwable != null) {
                status.set("Scan failed: " + message(throwable));
            } else {
                result.set(scanResult);
                if (scanResult.status() == ScanStatus.INVALID_ROOT) {
                    status.set("Selected path is not an accessible directory or drive.");
                } else {
                    openDirectory(scanResult.root());
                }
            }
        }));
    }

    public void openDirectory(Path directory) {
        ScanResult scanResult = result.get();
        if (scanResult == null || scanning.get()) {
            return;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        long requestId = ++listingRequestId;
        currentDirectory.set(normalized);
        status.set("Loading " + normalized + "...");
        scanService.listDirectoryAsync(normalized, scanResult).whenComplete((directoryListing, throwable) ->
                runOnUiThread(() -> {
                    if (requestId != listingRequestId) {
                        return;
                    }
                    if (throwable != null) {
                        status.set("Unable to open directory: " + message(throwable));
                    } else {
                        listing.set(directoryListing);
                        status.set(directoryListing.errors().isEmpty()
                                ? "Loaded " + normalized
                                : "Loaded with " + directoryListing.errors().size() + " warning(s)");
                    }
                }));
    }

    public void cancel() {
        if (activeScan != null) {
            activeScan.cancellationToken().cancel();
            status.set("Cancelling scan...");
        }
    }

    public BooleanProperty scanningProperty() {
        return scanning;
    }

    public LongProperty filesVisitedProperty() {
        return filesVisited;
    }

    public LongProperty directoriesVisitedProperty() {
        return directoriesVisited;
    }

    public LongProperty bytesVisitedProperty() {
        return bytesVisited;
    }

    public StringProperty statusProperty() {
        return status;
    }

    public ObjectProperty<ScanResult> resultProperty() {
        return result;
    }

    public ObjectProperty<DirectoryListingResult> listingProperty() {
        return listing;
    }

    public ObjectProperty<Path> currentDirectoryProperty() {
        return currentDirectory;
    }

    private void onProgress(ScanProgress progress) {
        runOnUiThread(() -> {
            filesVisited.set(progress.filesVisited());
            directoriesVisited.set(progress.directoriesVisited());
            bytesVisited.set(progress.bytesVisited());
            status.set(String.format("Scanning: %d files, %d folders | %s",
                    progress.filesVisited(), progress.directoriesVisited(), progress.currentPath()));
        });
    }

    private static void runOnUiThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    private static String message(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public void close() {
        cancel();
        scanService.close();
    }
}
