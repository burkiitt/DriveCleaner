package com.drivecleaner.cleanup;

import java.time.LocalDate;

public record InstalledApplication(
        String name,
        String version,
        String publisher,
        String installLocation,
        String uninstallCommand,
        LocalDate installDate,
        Long estimatedSizeBytes,
        String recommendation) {
}
