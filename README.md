# DiskPilot

DiskPilot is a Windows disk space analyzer and conservative cleanup assistant built with Java 21, JavaFX, Maven, and JUnit 5.

The application scans a selected directory or drive, calculates aggregate directory sizes, reports the largest files and folders, and exposes available file timestamps. It also provides a separate cleanup workflow for reviewable temporary-file/cache candidates and older installed applications.

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

Protected system and sensitive application locations are excluded from cleanup candidates. The cleanup tab only suggests known temporary folders and selected cache locations; it does not infer that a file is safe to remove from age or size alone.

## Cleanup safety

- Select individual candidates and use **Move selected to Recycle Bin**. The application never permanently deletes files.
- Files are rechecked immediately before the move, symbolic links are rejected, and protected locations can never become candidates.
- Older installed applications are discovered from Windows uninstall registry entries. Double-clicking one starts its registered Windows uninstaller; DiskPilot does not uninstall applications itself.
- Windows does not expose a reliable universal last-used timestamp for installed applications, so every recommendation requires user review.
- Access errors, missing files, registry failures, and other filesystem errors are shown as warnings instead of terminating the scan.
