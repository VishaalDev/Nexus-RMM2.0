package com.nexusrmm.dto;

import com.nexusrmm.entity.MetricSample;

import java.time.Instant;

public record CurrentMetricDto(
        String metricType,
        String instanceLabel,
        Double value,
        String unit,
        String availabilityStatus,
        String source,
        String diagnostic,
        Instant timestamp
) {
    public static CurrentMetricDto from(MetricSample s) {
        return new CurrentMetricDto(
                s.getMetricType().name(),
                s.getInstanceLabel(),
                s.getValue(),
                s.getUnit(),
                s.getAvailabilityStatus().name(),
                s.getSource(),
                s.getDiagnostic(),
                s.getTimestamp()
        );
    }
}
