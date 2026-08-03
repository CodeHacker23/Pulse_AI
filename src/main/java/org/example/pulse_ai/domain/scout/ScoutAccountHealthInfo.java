package org.example.pulse_ai.domain.scout;

/** Статус аккаунта из sidecar /health (top-level — стабильнее при bootRun/hot-reload). */
public record ScoutAccountHealthInfo(long id, String label, String type) {
}
