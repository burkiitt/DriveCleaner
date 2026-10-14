package com.drivecleaner.safety;

import java.nio.file.Path;

public record ProtectedLocation(Path path, String reason) {
}
