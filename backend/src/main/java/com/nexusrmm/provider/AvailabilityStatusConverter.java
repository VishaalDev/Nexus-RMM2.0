package com.nexusrmm.provider;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link AvailabilityStatus} to/from a plain String column, for the
 * same reason as {@code com.nexusrmm.entity.MetricTypeConverter}: it keeps
 * Hibernate from ever treating this as an enum-typed column, which is what
 * triggers its automatic (and, once the enum grows, eventually stale)
 * CHECK constraint generation. See docs/database.md.
 */
@Converter(autoApply = true)
public class AvailabilityStatusConverter implements AttributeConverter<AvailabilityStatus, String> {

    @Override
    public String convertToDatabaseColumn(AvailabilityStatus attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public AvailabilityStatus convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AvailabilityStatus.valueOf(dbData);
    }
}
