package com.drivecleaner.ui;

import com.drivecleaner.cleanup.CleanupCandidate;
import com.drivecleaner.cleanup.CleanupService;
import com.drivecleaner.cleanup.InstalledApplication;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ListProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class CleanupViewModel implements AutoCloseable {
    private final CleanupService cleanupService;
    private final ObjectProperty<Path> scanRoot = new SimpleObjectProperty<>();
    private final ListProperty<CleanupCandidateRow> candidates = new SimpleListProperty<>(
            FXCollections.observableArrayList());
    private final ListProperty<InstalledApplication> applications = new SimpleListProperty<>(
            FXCollections.observableArrayList());
    private final BooleanProperty working = new SimpleBooleanProperty();
    private final StringProperty status = new SimpleStringProperty("Run an analysis to see cleanup recommendations.");

    public CleanupViewModel(CleanupService cleanupService) {
        this.cleanupService = Objects.requireNonNull(cleanupService, "cleanupService");
    }

    public void setScanRoot(Path root) {
        scanRoot.set(root);
        candidates.clear();
        applications.clear();
        status.set("Run cleanup analysis to find review candidates.");
    }

    public void analyze() {
        Path root = scanRoot.get();
        if (root == null || working.get()) {
            return;
        }
        working.set(true);
        status.set("Analyzing temporary files, caches, and installed applications...");
        cleanupService.analyzeAsync(root).whenComplete((analysis, throwable) -> runOnUiThread(() -> {
            working.set(false);
            if (throwable != null) {
                status.set("Cleanup analysis failed: " + message(throwable));
                return;
            }
            candidates.setAll(analysis.candidates().stream().map(CleanupCandidateRow::new).toList());
            applications.setAll(analysis.applications());
            String suffix = analysis.truncated() ? " The list was limited to 5,000 files." : "";
            status.set(String.format("Found %d file candidate(s) and %d application(s) for review.%s",
                    analysis.candidates().size(), analysis.applications().size(), suffix));
        }));
    }

    public void moveSelectedToRecycleBin() {
        if (working.get()) {
            return;
        }
        List<CleanupCandidate> selected = candidates.stream()
                .filter(row -> row.selectedProperty().get())
                .map(CleanupCandidateRow::candidate)
                .toList();
        if (selected.isEmpty()) {
            status.set("Select at least one file candidate first.");
            return;
        }
        working.set(true);
        status.set("Moving selected files to the Windows Recycle Bin...");
        cleanupService.moveToRecycleBinAsync(selected).whenComplete((action, throwable) -> runOnUiThread(() -> {
            working.set(false);
            if (throwable != null) {
                status.set("Cleanup failed: " + message(throwable));
                return;
            }
            candidates.removeIf(row -> action.movedToRecycleBin().contains(row.candidate()));
            status.set(String.format("Moved %d file(s) to the Recycle Bin; %d warning(s).",
                    action.movedToRecycleBin().size(), action.errors().size()));
        }));
    }

    public void openUninstaller(InstalledApplication application) {
        if (working.get()) {
            return;
        }
        status.set("Starting the Windows uninstaller for " + application.name() + "...");
        cleanupService.openUninstallerAsync(application).whenComplete((ignored, throwable) -> runOnUiThread(() -> {
            if (throwable != null) {
                status.set("Unable to start uninstaller: " + message(throwable));
            } else {
                status.set("Windows uninstaller started for " + application.name() + ".");
            }
        }));
    }

    public ObjectProperty<Path> scanRootProperty() {
        return scanRoot;
    }

    public ListProperty<CleanupCandidateRow> candidatesProperty() {
        return candidates;
    }

    public ListProperty<InstalledApplication> applicationsProperty() {
        return applications;
    }

    public BooleanProperty workingProperty() {
        return working;
    }

    public StringProperty statusProperty() {
        return status;
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
        cleanupService.close();
    }
}
