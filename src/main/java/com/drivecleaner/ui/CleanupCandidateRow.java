package com.drivecleaner.ui;

import com.drivecleaner.cleanup.CleanupCandidate;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

public final class CleanupCandidateRow {
    private final CleanupCandidate candidate;
    private final BooleanProperty selected = new SimpleBooleanProperty(true);

    public CleanupCandidateRow(CleanupCandidate candidate) {
        this.candidate = candidate;
    }

    public CleanupCandidate candidate() {
        return candidate;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }
}
