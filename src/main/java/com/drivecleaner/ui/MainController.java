package com.drivecleaner.ui;

import com.drivecleaner.cleanup.CleanupCategory;
import com.drivecleaner.cleanup.InstalledApplication;
import com.drivecleaner.scan.DirectoryListingResult;
import com.drivecleaner.scan.ScanResult;
import com.drivecleaner.scan.ScanStatus;
import com.drivecleaner.scan.model.EntryKind;
import com.drivecleaner.scan.model.FileSystemEntry;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;

public final class MainController {
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(Locale.getDefault());
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.getDefault());

    private final ScanViewModel viewModel;
    private final CleanupViewModel cleanupViewModel;
    private final BorderPane root = new BorderPane();
    private final TextField pathField = new TextField();
    private final TextField searchField = new TextField();
    private final Button chooseButton = new Button("Choose directory...");
    private final Button startButton = new Button("Scan");
    private final Button cancelButton = new Button("Cancel");
    private final Button backButton = new Button("Back");
    private final Button openButton = new Button("Open folder");
    private final ProgressBar progressBar = new ProgressBar();
    private final Label statusLabel = new Label();
    private final Label summaryLabel = new Label("No scan completed.");
    private final Label currentDirectoryLabel = new Label();
    private final Label warningLabel = new Label();
    private final Label cleanupStatusLabel = new Label();
    private final ObservableList<FileSystemEntry> visibleEntries = FXCollections.observableArrayList();
    private final TableView<FileSystemEntry> analysisTable = new TableView<>();
    private final TableView<CleanupCandidateRow> cleanupTable = new TableView<>();
    private final TableView<InstalledApplication> applicationTable = new TableView<>();

    public MainController(ScanViewModel viewModel, CleanupViewModel cleanupViewModel) {
        this.viewModel = Objects.requireNonNull(viewModel, "viewModel");
        this.cleanupViewModel = Objects.requireNonNull(cleanupViewModel, "cleanupViewModel");
        configureAnalysisTable();
        configureCleanupTables();
        configureBindings();
        root.setTop(createTopBar());
        root.setCenter(createTabs());
        root.setPadding(new Insets(12));
    }

    public Parent view() {
        return root;
    }

    private HBox createTopBar() {
        pathField.setEditable(false);
        pathField.setPromptText("Select a drive or directory");
        pathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        chooseButton.setOnAction(event -> chooseDirectory());
        startButton.setOnAction(event -> startScan());
        cancelButton.setOnAction(event -> viewModel.cancel());
        progressBar.setPrefWidth(150);
        progressBar.setProgress(0);
        return new HBox(8, pathField, chooseButton, startButton, cancelButton, progressBar);
    }

    private TabPane createTabs() {
        Tab analysisTab = new Tab("Disk analysis", createAnalysisContent());
        analysisTab.setClosable(false);
        Tab cleanupTab = new Tab("Cleanup recommendations", createCleanupContent());
        cleanupTab.setClosable(false);
        TabPane tabs = new TabPane(analysisTab, cleanupTab);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        return tabs;
    }

    private VBox createAnalysisContent() {
        searchField.setPromptText("Search current directory...");
        searchField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        backButton.setOnAction(event -> openParentDirectory());
        openButton.setOnAction(event -> openSelectedDirectory());
        HBox navigation = new HBox(8, backButton, openButton, new Label("Filter:"), searchField);
        navigation.setAlignment(Pos.CENTER_LEFT);

        currentDirectoryLabel.setStyle("-fx-font-weight: bold;");
        warningLabel.setWrapText(true);
        warningLabel.setStyle("-fx-text-fill: #9a6700;");
        statusLabel.setWrapText(true);
        VBox content = new VBox(8,
                summaryLabel,
                navigation,
                currentDirectoryLabel,
                analysisTable,
                warningLabel,
                statusLabel,
                new Label("Folder sizes are calculated recursively. Windows last-access times may be stale or unavailable."));
        content.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(analysisTable, Priority.ALWAYS);
        return content;
    }

    private VBox createCleanupContent() {
        Button analyzeButton = new Button("Analyze cleanup");
        Button recycleButton = new Button("Move selected to Recycle Bin");
        analyzeButton.setOnAction(event -> cleanupViewModel.analyze());
        recycleButton.setOnAction(event -> cleanupViewModel.moveSelectedToRecycleBin());
        analyzeButton.disableProperty().bind(cleanupViewModel.workingProperty());
        recycleButton.disableProperty().bind(cleanupViewModel.workingProperty());

        Label explanation = new Label("Only known temporary files and caches are suggested. Review every item before moving it.");
        explanation.setWrapText(true);
        cleanupStatusLabel.setWrapText(true);
        HBox buttons = new HBox(8, analyzeButton, recycleButton);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label filesTitle = new Label("Temporary files and caches");
        filesTitle.setStyle("-fx-font-weight: bold;");
        Label applicationsTitle = new Label("Older installed applications");
        applicationsTitle.setStyle("-fx-font-weight: bold;");
        Label applicationNote = new Label("Windows does not provide a reliable last-used date here. Double-click an application to open its registered uninstaller; review it before removal.");
        applicationNote.setWrapText(true);
        VBox content = new VBox(8, explanation, buttons, cleanupStatusLabel, filesTitle,
                cleanupTable, applicationsTitle, applicationNote, applicationTable,
                new Label("No permanent deletion is performed. Selected files are moved to the Windows Recycle Bin."));
        content.setPadding(new Insets(12, 0, 0, 0));
        VBox.setVgrow(cleanupTable, Priority.ALWAYS);
        VBox.setVgrow(applicationTable, Priority.ALWAYS);
        return content;
    }

    private void configureBindings() {
        startButton.disableProperty().bind(viewModel.scanningProperty());
        cancelButton.disableProperty().bind(viewModel.scanningProperty().not());
        backButton.disableProperty().bind(viewModel.currentDirectoryProperty().isNull());
        openButton.disableProperty().bind(analysisTable.getSelectionModel().selectedItemProperty().isNull());
        statusLabel.textProperty().bind(viewModel.statusProperty());
        viewModel.scanningProperty().addListener((observable, oldValue, scanning) ->
                progressBar.setProgress(scanning ? ProgressBar.INDETERMINATE_PROGRESS : 0));
        viewModel.resultProperty().addListener((observable, oldResult, result) -> showResult(result));
        viewModel.listingProperty().addListener((observable, oldListing, listing) -> showListing(listing));
        viewModel.currentDirectoryProperty().addListener((observable, oldDirectory, directory) ->
                currentDirectoryLabel.setText(directory == null ? "" : directory.toString()));
        cleanupViewModel.candidatesProperty().addListener((observable, oldList, newList) -> cleanupTable.setItems(newList));
        cleanupViewModel.applicationsProperty().addListener((observable, oldList, newList) -> applicationTable.setItems(newList));
        cleanupStatusLabel.textProperty().bind(cleanupViewModel.statusProperty());
        cleanupTable.setItems(cleanupViewModel.candidatesProperty().get());
        applicationTable.setItems(cleanupViewModel.applicationsProperty().get());
    }

    private void configureAnalysisTable() {
        TableColumn<FileSystemEntry, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().name()));
        TableColumn<FileSystemEntry, String> typeColumn = new TableColumn<>("Type");
        typeColumn.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().kind() == EntryKind.DIRECTORY ? "Folder" : "File"));
        TableColumn<FileSystemEntry, Long> sizeColumn = new TableColumn<>("Size");
        sizeColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleObjectProperty<>(cell.getValue().size()));
        sizeColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Long item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : formatBytes(item));
            }
        });
        TableColumn<FileSystemEntry, String> extensionColumn = new TableColumn<>("Extension");
        extensionColumn.setCellValueFactory(cell -> new SimpleStringProperty(
                cell.getValue().directory() ? "" : cell.getValue().extension()));
        TableColumn<FileSystemEntry, String> modifiedColumn = timestampColumn("Last modified", entry -> entry.timestamps().lastModifiedTime());
        TableColumn<FileSystemEntry, String> accessedColumn = timestampColumn("Last accessed", entry -> entry.timestamps().lastAccessTime());
        TableColumn<FileSystemEntry, String> pathColumn = new TableColumn<>("Path");
        pathColumn.setCellValueFactory(cell -> new SimpleStringProperty(cell.getValue().path().toString()));
        analysisTable.getColumns().setAll(nameColumn, typeColumn, sizeColumn, extensionColumn,
                modifiedColumn, accessedColumn, pathColumn);
        analysisTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        FilteredList<FileSystemEntry> filtered = new FilteredList<>(visibleEntries, entry -> true);
        searchField.textProperty().addListener((observable, oldText, newText) -> {
            String query = newText == null ? "" : newText.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(entry -> query.isEmpty()
                    || entry.name().toLowerCase(Locale.ROOT).contains(query)
                    || entry.path().toString().toLowerCase(Locale.ROOT).contains(query));
        });
        SortedList<FileSystemEntry> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(analysisTable.comparatorProperty());
        analysisTable.setItems(sorted);
        analysisTable.setPlaceholder(new Label("Select a directory and run a scan."));
        analysisTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                openSelectedDirectory();
            }
        });
    }

    private void configureCleanupTables() {
        TableColumn<CleanupCandidateRow, Boolean> selectedColumn = new TableColumn<>("Select");
        selectedColumn.setCellValueFactory(cell -> cell.getValue().selectedProperty());
        selectedColumn.setCellFactory(CheckBoxTableCell.forTableColumn(selectedColumn));
        selectedColumn.setEditable(true);
        TableColumn<CleanupCandidateRow, String> nameColumn = stringColumn("Name", row -> row.candidate().name());
        TableColumn<CleanupCandidateRow, String> categoryColumn = stringColumn("Category", row -> categoryLabel(row.candidate().category()));
        TableColumn<CleanupCandidateRow, String> sizeColumn = stringColumn("Size", row -> formatBytes(row.candidate().size()));
        TableColumn<CleanupCandidateRow, String> modifiedColumn = stringColumn("Last modified", row -> formatTimestamp(row.candidate().lastModified()));
        TableColumn<CleanupCandidateRow, String> recommendationColumn = stringColumn("Recommendation", row -> row.candidate().recommendation());
        TableColumn<CleanupCandidateRow, String> pathColumn = stringColumn("Path", row -> row.candidate().path().toString());
        cleanupTable.getColumns().setAll(selectedColumn, nameColumn, categoryColumn, sizeColumn,
                modifiedColumn, recommendationColumn, pathColumn);
        cleanupTable.setEditable(true);
        cleanupTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        applicationTable.getColumns().setAll(
                stringColumn("Name", application -> application.name()),
                stringColumn("Version", application -> application.version()),
                stringColumn("Publisher", application -> application.publisher()),
                stringColumn("Installed", application -> formatDate(application.installDate())),
                stringColumn("Estimated size", application -> formatBytes(application.estimatedSizeBytes())),
                stringColumn("Recommendation", application -> application.recommendation()));
        applicationTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        applicationTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                InstalledApplication application = applicationTable.getSelectionModel().getSelectedItem();
                if (application != null) {
                    cleanupViewModel.openUninstaller(application);
                }
            }
        });
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose drive or directory to scan");
        String current = pathField.getText();
        File initial = current.isBlank() ? new File(System.getProperty("user.home")) : new File(current);
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        Window window = root.getScene() == null ? null : root.getScene().getWindow();
        File selected = chooser.showDialog(window);
        if (selected != null) {
            pathField.setText(selected.toPath().toAbsolutePath().normalize().toString());
            cleanupViewModel.setScanRoot(selected.toPath());
        }
    }

    private void startScan() {
        if (pathField.getText().isBlank()) {
            statusLabel.setText("Choose a directory or drive first.");
            return;
        }
        Path path = Path.of(pathField.getText()).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            statusLabel.setText("Selected path is not an accessible directory or drive.");
            return;
        }
        cleanupViewModel.setScanRoot(path);
        viewModel.start(path);
    }

    private void openSelectedDirectory() {
        FileSystemEntry selected = analysisTable.getSelectionModel().getSelectedItem();
        if (selected != null && selected.directory()) {
            viewModel.openDirectory(selected.path());
        }
    }

    private void openParentDirectory() {
        Path current = viewModel.currentDirectoryProperty().get();
        if (current != null && current.getParent() != null) {
            viewModel.openDirectory(current.getParent());
        }
    }

    private void showResult(ScanResult result) {
        visibleEntries.clear();
        if (result == null) {
            summaryLabel.setText("No scan completed.");
            warningLabel.setText("");
            return;
        }
        summaryLabel.setText(String.format("Total: %s in %d files and %d folders; skipped: %d; status: %s",
                formatBytes(result.totalBytes()), result.fileCount(), result.directoryCount(),
                result.skippedCount(), result.status()));
        warningLabel.setText(result.errors().isEmpty()
                ? ""
                : "Warnings: " + result.errors().size() + " filesystem item(s) could not be fully read.");
        if (result.status() == ScanStatus.INVALID_ROOT) {
            cleanupViewModel.setScanRoot(null);
        }
    }

    private void showListing(DirectoryListingResult listing) {
        visibleEntries.setAll(listing == null ? FXCollections.observableArrayList() : listing.entries());
        if (listing != null && !listing.errors().isEmpty()) {
            warningLabel.setText("Warnings in this directory: " + listing.errors().size());
        }
    }

    private static TableColumn<FileSystemEntry, String> timestampColumn(
            String title, java.util.function.Function<FileSystemEntry, Instant> value) {
        return stringColumn(title, entry -> formatTimestamp(value.apply(entry)));
    }

    private static <T> TableColumn<T, String> stringColumn(
            String title, java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private static String categoryLabel(CleanupCategory category) {
        return category == CleanupCategory.APPLICATION_CACHE ? "Application cache" : "Temporary file";
    }

    private static String formatTimestamp(Instant timestamp) {
        if (timestamp == null) {
            return "Unavailable";
        }
        return DATE_TIME_FORMATTER.format(timestamp.atZone(java.time.ZoneId.systemDefault()));
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "Unavailable" : DATE_FORMATTER.format(date);
    }

    private static String formatBytes(Long bytes) {
        if (bytes == null) {
            return "Unavailable";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }
}
