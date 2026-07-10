package org.example.pulse_ai.domain.request;

public enum RequestStatus {
    PENDING,
    COLLECTING_STATS,
    ANALYZING,
    GENERATING_IDEAS,
    GENERATING_POSTS,
    COMPLETED,
    FAILED,
    CANCELLED
}
