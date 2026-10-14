package com.drivecleaner.safety;

import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ProtectionPolicy {
    private final List<ProtectedLocation> protectedLocations;

    public ProtectionPolicy(List<ProtectedLocation> protectedLocations) {
        this.protectedLocations = protectedLocations.stream()
                .map(location -> new ProtectedLocation(normalize(location.path()), location.reason()))
                .sorted(Comparator.comparingInt((ProtectedLocation location) ->
                        location.path().toString().length()).reversed())
                .toList();
    }

    public static ProtectionPolicy windowsDefaults() {
        List<ProtectedLocation> locations = new ArrayList<>();
        addIfPresent(locations, System.getenv("SystemRoot"), "Windows system directory");
        addIfPresent(locations, System.getenv("ProgramFiles"), "Installed application directory");
        addIfPresent(locations, System.getenv("ProgramFiles(x86)"), "Installed application directory");
        addIfPresent(locations, System.getenv("ProgramData"), "Shared application data");

        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            addIfPresent(locations, Path.of(userHome, "AppData").toString(), "Per-user application data");
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (Path root : FileSystems.getDefault().getRootDirectories()) {
                addIfPresent(locations, root.resolve("Windows").toString(), "Windows system directory");
                addIfPresent(locations, root.resolve("Program Files").toString(), "Installed application directory");
                addIfPresent(locations, root.resolve("Program Files (x86)").toString(), "Installed application directory");
                addIfPresent(locations, root.resolve("ProgramData").toString(), "Shared application data");
                addIfPresent(locations, root.resolve("$Recycle.Bin").toString(), "Recycle Bin data");
                addIfPresent(locations, root.resolve("System Volume Information").toString(), "Volume metadata");
                addIfPresent(locations, root.resolve("pagefile.sys").toString(), "Windows paging file");
                addIfPresent(locations, root.resolve("hiberfil.sys").toString(), "Windows hibernation file");
                addIfPresent(locations, root.resolve("swapfile.sys").toString(), "Windows swap file");
            }
        }
        return new ProtectionPolicy(locations);
    }

    public Optional<ProtectedLocation> match(Path path) {
        Path normalized = normalize(path);
        String candidate = comparisonKey(normalized);
        return protectedLocations.stream()
                .filter(location -> isSameOrDescendant(candidate, comparisonKey(location.path())))
                .findFirst();
    }

    public List<ProtectedLocation> protectedLocations() {
        return protectedLocations;
    }

    private static void addIfPresent(List<ProtectedLocation> locations, String path, String reason) {
        if (path != null && !path.isBlank()) {
            locations.add(new ProtectedLocation(Path.of(path), reason));
        }
    }

    private static boolean isSameOrDescendant(String candidate, String parent) {
        return candidate.equals(parent) || candidate.startsWith(parent + "\\") || candidate.startsWith(parent + "/");
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String comparisonKey(Path path) {
        return path.toString().replace('/', '\\').toLowerCase(Locale.ROOT);
    }
}
