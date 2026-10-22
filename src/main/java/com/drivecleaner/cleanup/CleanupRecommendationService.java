package com.drivecleaner.cleanup;

import com.drivecleaner.safety.ProtectionPolicy;
import com.drivecleaner.scan.model.ScanError;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CleanupRecommendationService {
    private static final int MAX_CANDIDATES = 5_000;
    private static final Duration MINIMUM_AGE = Duration.ofDays(7);

    private final ProtectionPolicy protectionPolicy;
    private final Clock clock;
    private final InstalledApplicationService installedApplicationService;
    private final List<CandidateRoot> configuredCandidateRoots;

    public CleanupRecommendationService(ProtectionPolicy protectionPolicy) {
        this(protectionPolicy, Clock.systemDefaultZone(), new InstalledApplicationService(), null);
    }

    CleanupRecommendationService(ProtectionPolicy protectionPolicy, Clock clock,
                                 InstalledApplicationService installedApplicationService) {
        this(protectionPolicy, clock, installedApplicationService, null);
    }

    CleanupRecommendationService(ProtectionPolicy protectionPolicy, Clock clock,
                                 InstalledApplicationService installedApplicationService,
                                 List<Path> candidateRoots) {
        this.protectionPolicy = protectionPolicy;
        this.clock = clock;
        this.installedApplicationService = installedApplicationService;
        this.configuredCandidateRoots = candidateRoots == null ? null : candidateRoots.stream()
                .map(path -> new CandidateRoot(path.toAbsolutePath().normalize(), CleanupCategory.TEMPORARY_FILE))
                .toList();
    }

    public CleanupAnalysisResult analyze(Path scanRoot) {
        Path normalizedRoot = scanRoot.toAbsolutePath().normalize();
        List<CleanupCandidate> candidates = new ArrayList<>();
        List<ScanError> errors = new ArrayList<>();
        boolean[] truncated = {false};
        Instant cutoff = clock.instant().minus(MINIMUM_AGE);

        for (CandidateRoot candidateRoot : candidateRoots()) {
            Path normalizedCandidateRoot = candidateRoot.path().toAbsolutePath().normalize();
            if (!isSameOrDescendant(normalizedCandidateRoot, normalizedRoot)
                    && !isSameOrDescendant(normalizedRoot, normalizedCandidateRoot)) {
                continue;
            }
            if (!Files.isDirectory(normalizedCandidateRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalizedCandidateRoot)) {
                continue;
            }
            try {
                Files.walkFileTree(normalizedCandidateRoot, new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                        if (protectionPolicy.match(directory).isPresent()) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                        if (candidates.size() >= MAX_CANDIDATES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        if (attributes.isRegularFile() && !attributes.isSymbolicLink()
                                && attributes.lastModifiedTime().toInstant().isBefore(cutoff)
                                && protectionPolicy.match(file).isEmpty()) {
                            candidates.add(new CleanupCandidate(
                                    file.toAbsolutePath().normalize(),
                                    file.getFileName().toString(),
                                    attributes.size(),
                                    candidateRoot.category(),
                                    attributes.lastModifiedTime().toInstant(),
                                    recommendationFor(candidateRoot.category())));
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exception) {
                        errors.add(new ScanError(file, "cleanup analysis", message(exception)));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path directory, IOException exception) {
                        if (exception != null) {
                            errors.add(new ScanError(directory, "cleanup analysis", message(exception)));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException exception) {
                errors.add(new ScanError(normalizedCandidateRoot, "cleanup analysis", message(exception)));
            }
            if (truncated[0]) {
                break;
            }
        }

        InstalledApplicationService.DiscoveryResult applications = installedApplicationService.findReviewCandidates();
        errors.addAll(applications.errors());
        return new CleanupAnalysisResult(normalizedRoot, candidates, applications.applications(), errors, truncated[0]);
    }

    private List<CandidateRoot> candidateRoots() {
        if (configuredCandidateRoots != null) {
            return configuredCandidateRoots;
        }
        Map<String, CandidateRoot> roots = new LinkedHashMap<>();
        addRoot(roots, System.getenv("TEMP"), CleanupCategory.TEMPORARY_FILE);
        addRoot(roots, System.getenv("TMP"), CleanupCategory.TEMPORARY_FILE);
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            addRoot(roots, Path.of(userHome, "AppData", "Local", "Temp").toString(), CleanupCategory.TEMPORARY_FILE);
            addRoot(roots, Path.of(userHome, "AppData", "Local", "D3DSCache").toString(), CleanupCategory.APPLICATION_CACHE);
            addRoot(roots, Path.of(userHome, "AppData", "Local", "NVIDIA", "DXCache").toString(), CleanupCategory.APPLICATION_CACHE);
            addRoot(roots, Path.of(userHome, "AppData", "Local", "NVIDIA", "GLCache").toString(), CleanupCategory.APPLICATION_CACHE);
        }
        return List.copyOf(roots.values());
    }

    private static void addRoot(Map<String, CandidateRoot> roots, String path, CleanupCategory category) {
        if (path == null || path.isBlank()) {
            return;
        }
        Path normalized = Path.of(path).toAbsolutePath().normalize();
        roots.putIfAbsent(comparisonKey(normalized), new CandidateRoot(normalized, category));
    }

    private static boolean isSameOrDescendant(Path candidate, Path parent) {
        String candidateKey = comparisonKey(candidate);
        String parentKey = comparisonKey(parent);
        return candidateKey.equals(parentKey)
                || candidateKey.startsWith(parentKey + "\\")
                || candidateKey.startsWith(parentKey + "/");
    }

    private static String comparisonKey(Path path) {
        return path.toString().replace('/', '\\').toLowerCase(Locale.ROOT);
    }

    private static String recommendationFor(CleanupCategory category) {
        return category == CleanupCategory.TEMPORARY_FILE
                ? "Temporary file older than 7 days. Review it before moving it to the Recycle Bin."
                : "Application cache older than 7 days. Confirm the application is closed before cleanup.";
    }

    private static String message(IOException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private record CandidateRoot(Path path, CleanupCategory category) {
    }
}
