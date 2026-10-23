package com.nexusrmm.dto;

import com.nexusrmm.entity.HardwareEvent;

import java.time.Instant;

public record HardwareEventDto(
        Integer windowsEventId,
        String providerName,
        Long recordId,
        String level,
        String category,
        Instant occurredAt,
        String message
) {
    public static HardwareEventDto from(HardwareEvent e) {
        return new HardwareEventDto(
                e.getWindowsEventId(), e.getProviderName(), e.getRecordId(),
                e.getLevel(), e.getCategory(), e.getOccurredAt(), e.getMessage()
        );
    }
}
