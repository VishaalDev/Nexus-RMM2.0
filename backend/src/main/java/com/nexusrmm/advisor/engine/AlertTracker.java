package com.nexusrmm.advisor.engine;

import com.nexusrmm.advisor.model.AlertEvent;
import com.nexusrmm.advisor.model.AlertType;
import com.nexusrmm.advisor.model.Issue;
import com.nexusrmm.health.model.Severity;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which issues are open so the advisor can say "since when", and
 * logs NEW / ESCALATED / RESOLVED transitions for WARNING and CRITICAL
 * issues (INFO issues are tracked for their age but never alert).
 *
 * <p>Flapping control: an issue that disappears is only declared resolved
 * after it has been absent for {@code resolveAfterAbsent} consecutive
 * evaluations; if it returns sooner it keeps its original first-seen time
 * and no events are logged. State is in memory and bounded: the alert log
 * keeps the newest {@code maxEvents} entries, and open state is bounded by
 * the (small) number of distinct issue keys. It resets on restart.
 */
public final class AlertTracker {

    private static final class State {
        Instant firstSeen;
        Instant lastSeen;
        Severity severity;
        Severity worstSeverity;
        String category;
        String title;
        int absent;
    }

    private final int resolveAfterAbsent;
    private final int maxEvents;
    private final Map<String, State> open = new LinkedHashMap<>();
    private final Deque<AlertEvent> events = new ArrayDeque<>();

    public AlertTracker(int resolveAfterAbsent, int maxEvents) {
        if (resolveAfterAbsent < 1 || maxEvents < 1) {
            throw new IllegalArgumentException("resolveAfterAbsent and maxEvents must be >= 1");
        }
        this.resolveAfterAbsent = resolveAfterAbsent;
        this.maxEvents = maxEvents;
    }

    /** Folds one evaluation into the state and returns the same issues with first/last-seen filled in. */
    public synchronized List<Issue> update(List<Issue> current, Instant now) {
        List<Issue> out = new ArrayList<>(current.size());
        Map<String, Boolean> present = new HashMap<>();
        for (Issue issue : current) {
            present.put(issue.key(), true);
        }

        // Resolutions are logged first so that, newest-first, NEW and ESCALATED entries of the same
        // evaluation appear above them.
        Iterator<Map.Entry<String, State>> it = open.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, State> e = it.next();
            if (present.containsKey(e.getKey())) {
                continue;
            }
            State st = e.getValue();
            st.absent++;
            if (st.absent >= resolveAfterAbsent) {
                if (alerting(st.worstSeverity)) {
                    events.addFirst(new AlertEvent(now, AlertType.RESOLVED, e.getKey(), st.category, st.worstSeverity, st.title));
                    trim();
                }
                it.remove();
            }
        }

        for (Issue issue : current) {
            State st = open.get(issue.key());
            if (st == null) {
                st = new State();
                st.firstSeen = now;
                st.severity = issue.severity();
                st.worstSeverity = issue.severity();
                open.put(issue.key(), st);
                if (alerting(issue.severity())) {
                    log(now, AlertType.NEW, issue);
                }
            } else {
                if (issue.severity().ordinal() > st.severity.ordinal() && alerting(issue.severity())) {
                    log(now, AlertType.ESCALATED, issue);
                }
                st.severity = issue.severity();
                if (issue.severity().ordinal() > st.worstSeverity.ordinal()) {
                    st.worstSeverity = issue.severity();
                }
            }
            st.absent = 0;
            st.lastSeen = now;
            st.category = issue.category();
            st.title = issue.title();
            out.add(issue.tracked(st.firstSeen, st.lastSeen));
        }

        return out;
    }

    /** Newest first, at most {@code limit}. */
    public synchronized List<AlertEvent> recent(int limit) {
        List<AlertEvent> out = new ArrayList<>();
        for (AlertEvent e : events) {
            if (out.size() >= limit) {
                break;
            }
            out.add(e);
        }
        return out;
    }

    private static boolean alerting(Severity s) {
        return s == Severity.WARNING || s == Severity.CRITICAL;
    }

    private void log(Instant now, AlertType type, Issue issue) {
        events.addFirst(new AlertEvent(now, type, issue.key(), issue.category(), issue.severity(), issue.title()));
        trim();
    }

    private void trim() {
        while (events.size() > maxEvents) {
            events.removeLast();
        }
    }
}
