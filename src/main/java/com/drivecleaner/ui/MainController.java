package com.drivecleaner.ui;

import com.drivecleaner.scan.DirectoryListingResult;
import com.drivecleaner.scan.ScanResult;
import com.drivecleaner.scan.model.EntryKind;
import com.drivecleaner.scan.model.FileSystemEntry;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Function;

public final class MainController {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ScanViewModel viewModel;
    private final BorderPane root = new BorderPane();
    private final TextField pathField = new TextField();
    private final TextField searchField = new TextField();
    private final Button startButton = new Button("Start scan");
    private final Button cancelButton = new Button("Cancel");
    private final Button backButton = new Button("Back");
    private final Button openButton = new Button("Open folder");
    private final ProgressBar progressBar = new ProgressBar();
    private final Label statusLabel = new Label();
    private final Label summaryLabel = new Label();
    private final Label currentDirectoryLabel = new Label();
    private final Label warningsLabel = new Label();
    private final TableView<FileSystemEntry> entriesTable = new TableView<>();
    private final ObservableList<FileSystemEntry> entries = FXCollections.observableArrayList();
    private final FilteredList<FileSystemEntry> filteredEntries = new FilteredList<>(entries);
    private final SortedList<FileSystemEntry> sortedEntries = new SortedList<>(filteredEntries);

    public MainController(ScanViewModel viewModel) {
        this.viewModel = viewModel;
        configure();
    }

    public BorderPane view() {
        return root;
    }

    private void configure() {
        pathField.setEditable(false);
        pathField.setPromptText("Choose a directory or drive");
        searchField.setPromptText("Search name or path");

        Button browseButton = new Button("Choose directory...");
        browseButton.setOnAction(event -> chooseDirectory());
        startButton.setOnAction(event -> startScan());
        cancelButton.setOnAction(event -> viewModel.cancel());
        backButton.setOnAction(event -> navigateBack());
        openButton.setOnAction(event -> openSelectedFolder());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> updateFilter(newValue));

        cancelButton.disableProperty().bind(viewModel.scanningProperty().not());
        startButton.disableProperty().bind(viewModel.scanningProperty());
        backButton.disableProperty().bind(Bindings.createBooleanBinding(
                this::isAtScanRoot, viewModel.currentDirectoryProperty(), viewModel.resultProperty()));
        openButton.disableProperty().bind(Bindings.createBooleanBinding(
                () -> entriesTable.getSelectionModel().getSelectedItem() == null
                        || !entriesTable.getSelectionModel().getSelectedItem().directory(),
                entriesTable.getSelectionModel().selectedItemProperty()));
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.progressProperty().bind(Bindings.createDoubleBinding(
                () -> viewModel.scanningProperty().get() ? -1.0 : 0.0,
                viewModel.scanningProperty()));
        statusLabel.textProperty().bind(viewModel.statusProperty());

        HBox controls = new HBox(8, pathField, browseButton, startButton, cancelButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        VBox top = new VBox(8, controls, progressBar, statusLabel);
        top.setPadding(new Insets(12));
        root.setTop(top);

        configureTable();
        HBox navigation = new HBox(8, backButton, openButton, currentDirectoryLabel);
        HBox.setHgrow(currentDirectoryLabel, Priority.ALWAYS);
        HBox search = new HBox(8, new Label("Filter:"), searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        VBox content = new VBox(8, summaryLabel, navigation, search, entriesTable, warningsLabel,
                new Label("Folder sizes are calculated recursively. Last-access times may be stale or unavailable on Windows."));
        content.setPadding(new Insets(0, 12, 12, 12));
        VBox.setVgrow(entriesTable, Priority.ALWAYS);
        root.setCenter(content);

        entriesTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedFolder();
            }
        });
        viewModel.resultProperty().addListener((observable, oldResult, newResult) -> showResult(newResult));
        viewModel.listingProperty().addListener((observable, oldListing, newListing) -> showListing(newListing));
        viewModel.currentDirectoryProperty().addListener((observable, oldPath, newPath) ->
                currentDirectoryLabel.setText(newPath == null ? "" : newPath.toString()));
        entriesTable.setItems(sortedEntries);
    }

    private void configureTable() {
        TableColumn<FileSystemEntry, String> nameColumn = textColumn("Name", FileSystemEntry::name);
        TableColumn<FileSystemEntry, String> typeColumn = textColumn("Type", entry ->
                entry.kind() == EntryKind.DIRECTORY ? "Folder" : "File");
        TableColumn<FileSystemEntry, Long> sizeColumn = new TableColumn<>("Size (calculated)");
        sizeColumn.setCellValueFactory(cell -> new SimpleObjectProperty<>(cell.getValue().size()));
        sizeColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : formatBytes(item));
            }
        });
        TableColumn<FileSystemEntry, Instant> modifiedColumn = dateColumn("Last modified",
                entry -> entry.timestamps().lastModifiedTime());
        TableColumn<FileSystemEntry, Instant> accessedColumn = dateColumn("Last accessed (may be stale)",
                entry -> entry.timestamps().lastAccessTime());
        TableColumn<FileSystemEntry, String> pathColumn = textColumn("Path", entry -> entry.path().toString());

        entriesTable.getColumns().setAll(nameColumn, typeColumn, sizeColumn, modifiedColumn,
                accessedColumn, pathColumn);
        entriesTable.getSortOrder().add(nameColumn);
        entriesTable.sort();
        sortedEntries.comparatorProperty().bind(entriesTable.comparatorProperty());
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose directory or drive");
        Path home = Path.of(System.getProperty("user.home", "."));
        if (Files.isDirectory(home)) {
            chooser.setInitialDirectory(home.toFile());
        }
        java.io.File selected = chooser.showDialog(root.getScene().getWindow());
        if (selected != null) {
            pathField.setText(selected.toPath().toString());
        }
    }

    private void startScan() {
        if (pathField.getText().isBlank()) {
            viewModel.statusProperty().set("Choose a directory or drive first.");
            return;
        }
        try {
            viewModel.start(Paths.get(pathField.getText()));
        } catch (InvalidPathException | NullPointerException exception) {
            viewModel.statusProperty().set("Invalid path");
        }
    }

    private void navigateBack() {
        Path current = viewModel.currentDirectoryProperty().get();
        if (current != null && !isAtScanRoot()) {
            Path parent = current.getParent();
            if (parent != null) {
                viewModel.openDirectory(parent);
            }
        }
    }

    private void openSelectedFolder() {
        FileSystemEntry selected = entriesTable.getSelectionModel().getSelectedItem();
        if (selected != null && selected.directory()) {
            viewModel.openDirectory(selected.path());
        }
    }

    private boolean isAtScanRoot() {
        ScanResult result = viewModel.resultProperty().get();
        Path current = viewModel.currentDirectoryProperty().get();
        return result == null || current == null || current.equals(result.root());
    }

    private void updateFilter(String query) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        filteredEntries.setPredicate(entry -> normalizedQuery.isEmpty()
                || entry.name().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                || entry.path().toString().toLowerCase(Locale.ROOT).contains(normalizedQuery));
    }

    private void showResult(ScanResult result) {
        entries.clear();
        warningsLabel.setText("");
        if (result == null) {
            summaryLabel.setText("");
            return;
        }
        summaryLabel.setText(String.format("%s | %d files | %d folders | %s | %d skipped | %d warnings",
                result.status(), result.fileCount(), result.directoryCount(), formatBytes(result.totalBytes()),
                result.skippedCount(), result.errors().size()));
        if (!result.errors().isEmpty()) {
            warningsLabel.setText("Scan completed with warnings. Protected locations are informational only; no cleanup actions are available.");
        }
    }

    private void showListing(DirectoryListingResult listing) {
        if (listing == null) {
            entries.clear();
            return;
        }
        entries.setAll(listing.entries());
        if (!listing.errors().isEmpty()) {
            warningsLabel.setText(String.format("%d item(s) skipped or unavailable in this folder.",
                    listing.skippedCount()));
        } else {
            warningsLabel.setText("");
        }
    }

    private static TableColumn<FileSystemEntry, String> textColumn(String title,
                                                                    Function<FileSystemEntry, String> value) {
        TableColumn<FileSystemEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        column.setPrefWidth(160);
        return column;
    }

    private static TableColumn<FileSystemEntry, Instant> dateColumn(String title,
                                                                     Function<FileSystemEntry, Instant> value) {
        TableColumn<FileSystemEntry, Instant> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleObjectProperty<>(value.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Instant item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : formatTimestamp(item));
            }
        });
        column.setPrefWidth(190);
        return column;
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null) {
            return "Unavailable";
        }
        if (bytes < 1_024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1_024 && unit < units.length - 1) {
            value /= 1_024;
            unit++;
        }
        return String.format("%.1f %s", value, units[unit]);
    }

    private static String formatTimestamp(Instant timestamp) {
        return timestamp == null ? "Unavailable" : TIMESTAMP_FORMATTER.format(timestamp);
    }
}
