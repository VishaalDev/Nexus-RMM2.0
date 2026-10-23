package com.nexusrmm.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Maps {@link MetricType} to/from a plain String column.
 *
 * This exists specifically instead of {@code @Enumerated(EnumType.STRING)}.
 * Hibernate 6's schema generator adds an automatic SQL CHECK constraint
 * enumerating every current enum value for {@code @Enumerated(STRING)}
 * columns — and an explicit {@code @Column(columnDefinition = ...)} was
 * tried first to suppress it, but was confirmed (by inspecting the actual
 * generated schema, not just source) to NOT suppress it under this
 * project's Hibernate 6.4.4.Final + hibernate-community-dialects +
 * SQLiteDialect combination. An {@link AttributeConverter} sidesteps the
 * problem at its root: Hibernate's type system resolves this attribute as
 * a String from the start, so the enum-specific constraint-generation code
 * path — which is gated on the attribute being resolved as an enum type —
 * never runs at all. Nothing is being suppressed after the fact; there is
 * nothing to suppress.
 *
 * Enum validity is still fully enforced at the Java layer:
 * {@link #convertToEntityAttribute} calls {@link MetricType#valueOf},
 * which throws on any string that isn't a real constant — a corrupted or
 * hand-edited row is caught loudly on read, not silently accepted.
 *
 * See docs/database.md for the full incident writeup.
 */
@Converter(autoApply = true)
public class MetricTypeConverter implements AttributeConverter<MetricType, String> {

    @Override
    public String convertToDatabaseColumn(MetricType attribute) {
        return attribute == null ? null : attribute.name();
    }

    @Override
    public MetricType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : MetricType.valueOf(dbData);
    }
}
