package com.drivecleaner.cleanup;

import com.drivecleaner.scan.model.ScanError;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InstalledApplicationService {
    private static final DateTimeFormatter INSTALL_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> REGISTRY_ROOTS = List.of(
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall");

    public DiscoveryResult findReviewCandidates() {
        if (!isWindows()) {
            return new DiscoveryResult(List.of(), List.of());
        }

        List<InstalledApplication> applications = new ArrayList<>();
        List<ScanError> errors = new ArrayList<>();
        for (String registryRoot : REGISTRY_ROOTS) {
            readRegistryRoot(registryRoot, applications, errors);
        }
        return new DiscoveryResult(applications.stream().distinct().toList(), errors);
    }

    private void readRegistryRoot(String registryRoot, List<InstalledApplication> applications,
                                  List<ScanError> errors) {
        Process process = null;
        try {
            process = new ProcessBuilder("reg.exe", "query", registryRoot, "/s")
                    .redirectErrorStream(true)
                    .start();
            Map<String, String> values = new HashMap<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("HKEY_")) {
                        addApplication(values, applications);
                        values = new HashMap<>();
                    } else {
                        parseValue(line, values);
                    }
                }
            }
            addApplication(values, applications);
            if (process.waitFor() != 0) {
                errors.add(new ScanError(null, "read installed applications",
                        "Registry query failed for " + registryRoot));
            }
        } catch (IOException exception) {
            errors.add(new ScanError(null, "read installed applications", message(exception)));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            errors.add(new ScanError(null, "read installed applications", "Registry query interrupted"));
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void parseValue(String line, Map<String, String> values) {
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s{2,}", 3);
        if (parts.length == 3) {
            values.put(parts[0], parts[2].trim());
        }
    }

    private static void addApplication(Map<String, String> values, List<InstalledApplication> applications) {
        String name = values.get("DisplayName");
        String uninstallCommand = values.get("UninstallString");
        if (name == null || name.isBlank() || uninstallCommand == null || uninstallCommand.isBlank()) {
            return;
        }
        if ("0x1".equalsIgnoreCase(values.get("SystemComponent"))
                || "Update".equalsIgnoreCase(values.get("ReleaseType"))
                || "Security Update".equalsIgnoreCase(values.get("ReleaseType"))) {
            return;
        }
        LocalDate installDate = parseDate(values.get("InstallDate"));
        if (installDate == null || !installDate.isBefore(LocalDate.now().minusDays(180))) {
            return;
        }
        Long estimatedSizeBytes = parseEstimatedSize(values.get("EstimatedSize"));
        applications.add(new InstalledApplication(
                name,
                values.getOrDefault("DisplayVersion", ""),
                values.getOrDefault("Publisher", ""),
                values.getOrDefault("InstallLocation", ""),
                uninstallCommand,
                installDate,
                estimatedSizeBytes,
                "Installed more than 180 days ago. Windows does not provide a reliable last-used date; review the application before uninstalling."));
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim(), INSTALL_DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static Long parseEstimatedSize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.multiplyExact(Long.parseLong(value.trim()), 1_024L);
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String message(IOException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    public record DiscoveryResult(List<InstalledApplication> applications, List<ScanError> errors) {
        public DiscoveryResult {
            applications = List.copyOf(applications);
            errors = List.copyOf(errors);
        }
    }
}
