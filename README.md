# DiskPilot

DiskPilot is a read-only Windows disk space analyzer built with Java 21, JavaFX, Maven, and JUnit 5.

The current version scans a selected directory or drive, calculates aggregate directory sizes, reports the largest files and folders, and exposes available file timestamps. It does not delete, move, rename, or modify files.

## Requirements

- Java 21 LTS
- Maven 3.9 or newer

## Run

```text
mvn test
mvn javafx:run
```

The application runs natively as a Windows JavaFX desktop application. Use **Choose directory...** to select a drive or directory; no environment variable is required.

The table shows the selected directory's direct children. Double-click a folder or use **Open folder** to navigate into it. **Back** returns to the parent directory. Use the column headers to sort and the filter box to search the current directory.

The scanner does not follow symbolic links by default. Access failures, disappearing files, and other filesystem errors are reported as warnings and do not terminate the whole scan. Windows last-access times can be stale or unavailable.

Protected system and application locations are marked in scan records and are reserved from any future cleanup workflow. The current application has no cleanup workflow.
