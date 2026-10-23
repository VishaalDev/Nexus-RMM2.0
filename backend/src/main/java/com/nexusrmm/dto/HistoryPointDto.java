package com.nexusrmm.dto;

import com.nexusrmm.entity.MetricSample;

import java.time.Instant;

public record HistoryPointDto(
        Instant timestamp,
        Double value,
        String availabilityStatus
) {
    public static HistoryPointDto from(MetricSample s) {
        return new HistoryPointDto(s.getTimestamp(), s.getValue(), s.getAvailabilityStatus().name());
    }
}
