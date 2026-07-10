package org.example.pulse_ai.stats.external;

public interface ExternalMetricsProvider {

    String sourceName();

    ExternalChannelMetrics fetch(String username);
}
