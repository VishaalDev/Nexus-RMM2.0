package com.nexusrmm.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Part Z (timestamp audit) follow-through for the tables V2 did not cover.
 *
 * {@code hardware_event.occurred_at}, {@code hardware_event.stored_at},
 * {@code computer.first_seen_at} and {@code computer.last_seen_at} all map a
 * {@code java.time.Instant}, so they are all read through
 * {@code InstantEpochMillisConverter} ({@code autoApply = true}), i.e. with
 * {@code getLong()}. A row left in any legacy text form would not fail
 * loudly - it would silently read back as a 1970 instant (see
 * {@link TimestampNormalizer}). On the audited development database these
 * tables were already canonical (computer: INTEGER epoch millis;
 * hardware_event: empty), so on that machine this migration is expected to
 * be a verified no-op; it exists so no other install can be left exposed.
 *
 * Same contract as V2: every row of every target column is parsed
 * <em>before</em> any UPDATE is issued, so one unrecognizable value aborts
 * the whole migration with nothing changed; row counts are re-verified;
 * canonical rows are never touched.
 *
 * {@link #applicable} is false until at least one target table exists. A
 * brand-new database has no such tables until Hibernate creates them (with
 * canonical values from the start), so the runner skips - and does NOT
 * record - this migration then; it applies, as a no-op over canonical
 * rows, on the first start after the tables exist.
 */
final class V3NormalizeHardwareEventAndComputerTimestamps implements SchemaMigrator.Migration {

    private static final Logger log = LoggerFactory.getLogger(V3NormalizeHardwareEventAndComputerTimestamps.class);

    private static final String[][] TARGETS = {
            { "hardware_event", "occurred_at" },
            { "hardware_event", "stored_at" },
            { "computer", "first_seen_at" },
            { "computer", "last_seen_at" },
    };

    @Override
    public int version() {
        return 3;
    }

    @Override
    public String description() {
        return "Normalize hardware_event and computer Instant columns to canonical epoch milliseconds";
    }

    @Override
    public boolean applicable(Connection conn) throws Exception {
        for (String[] target : TARGETS) {
            if (TimestampNormalizer.tableExists(conn, target[0])) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void apply(Connection conn) throws Exception {
        Map<String, Long> countsBefore = new LinkedHashMap<>();
        List<TimestampNormalizer.Plan> plans = new ArrayList<>();

        // Phase 1: plan every column. Any unparseable row throws here, before any write.
        for (String[] target : TARGETS) {
            String table = target[0];
            String column = target[1];
            if (!TimestampNormalizer.tableExists(conn, table) || !TimestampNormalizer.columnExists(conn, table, column)) {
                log.info("{}.{} not present; skipping.", table, column);
                continue;
            }
            countsBefore.putIfAbsent(table, TimestampNormalizer.count(conn, table));
            plans.add(TimestampNormalizer.plan(conn, table, column));
        }

        // Phase 2: apply.
        int rewritten = 0;
        for (TimestampNormalizer.Plan plan : plans) {
            if (plan.implausible > 0) {
                log.warn("{}.{} has {} canonical value(s) outside 2000-2100 (epoch seconds stored as milliseconds?). "
                        + "Left untouched - a migration cannot know the intended value.",
                        plan.table, plan.column, plan.implausible);
            }
            if (!plan.pending.isEmpty()) {
                log.warn("Normalizing {} row(s) in {}.{} ({} already canonical).",
                        plan.pending.size(), plan.table, plan.column, plan.alreadyCanonical);
            }
            TimestampNormalizer.apply(conn, plan);
            rewritten += plan.pending.size();
        }

        // Phase 3: verify no row was added or lost.
        for (Map.Entry<String, Long> entry : countsBefore.entrySet()) {
            long after = TimestampNormalizer.count(conn, entry.getKey());
            if (after != entry.getValue()) {
                throw new IllegalStateException("Row count changed in " + entry.getKey() + " during timestamp "
                        + "normalization (before=" + entry.getValue() + ", after=" + after + "); aborting.");
            }
        }
        log.info("Timestamp audit v3 complete: {} value(s) rewritten across {} column(s).", rewritten, plans.size());
    }
}
