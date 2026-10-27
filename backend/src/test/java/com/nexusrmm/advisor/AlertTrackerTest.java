package com.nexusrmm.advisor;

import com.nexusrmm.advisor.engine.AlertTracker;
import com.nexusrmm.advisor.model.AlertEvent;
import com.nexusrmm.advisor.model.AlertType;
import com.nexusrmm.advisor.model.Issue;
import com.nexusrmm.health.model.Confidence;
import com.nexusrmm.health.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.nexusrmm.health.support.FakeHealthData.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertTrackerTest {

    private static Issue issue(String key, Severity s) {
        return new Issue(key, "CPU", s, "Title " + key, "summary", List.of("CODE"), null, null, Confidence.MEDIUM, 100, null, null);
    }

    private static Instant at(int minutes) {
        return NOW.plus(Duration.ofMinutes(minutes));
    }

    @Test
    void aNewWarningRaisesOneNewAlertAndGetsFirstSeen() {
        AlertTracker t = new AlertTracker(2, 100);
        List<Issue> out = t.update(List.of(issue("a", Severity.WARNING)), at(0));
        assertEquals(at(0), out.get(0).firstSeen());
        assertEquals(at(0), out.get(0).lastSeen());
        assertEquals(1, t.recent(10).size());
        assertEquals(AlertType.NEW, t.recent(10).get(0).type());
        t.update(List.of(issue("a", Severity.WARNING)), at(5));
        assertEquals(1, t.recent(10).size(), "an ongoing issue must not re-alert");
    }

    @Test
    void firstSeenIsKeptWhileLastSeenAdvances() {
        AlertTracker t = new AlertTracker(2, 100);
        t.update(List.of(issue("a", Severity.WARNING)), at(0));
        Issue later = t.update(List.of(issue("a", Severity.WARNING)), at(30)).get(0);
        assertEquals(at(0), later.firstSeen());
        assertEquals(at(30), later.lastSeen());
    }

    @Test
    void infoIssuesAreTrackedButNeverAlert() {
        AlertTracker t = new AlertTracker(1, 100);
        Issue tracked = t.update(List.of(issue("i", Severity.INFO)), at(0)).get(0);
        assertNotNull(tracked.firstSeen());
        t.update(List.of(), at(1));
        assertTrue(t.recent(10).isEmpty());
    }

    @Test
    void escalationIsLoggedButDeEscalationIsNot() {
        AlertTracker t = new AlertTracker(2, 100);
        t.update(List.of(issue("a", Severity.WARNING)), at(0));
        t.update(List.of(issue("a", Severity.CRITICAL)), at(1));
        t.update(List.of(issue("a", Severity.WARNING)), at(2));
        List<AlertEvent> e = t.recent(10);
        assertEquals(2, e.size());
        assertEquals(AlertType.ESCALATED, e.get(0).type());
        assertEquals(Severity.CRITICAL, e.get(0).severity());
        assertEquals(AlertType.NEW, e.get(1).type());
    }

    @Test
    void anInfoIssueThatBecomesAWarningIsAnEscalationWorthAlerting() {
        AlertTracker t = new AlertTracker(2, 100);
        t.update(List.of(issue("a", Severity.INFO)), at(0));
        t.update(List.of(issue("a", Severity.WARNING)), at(1));
        assertEquals(AlertType.ESCALATED, t.recent(10).get(0).type());
    }

    @Test
    void anIssueIsResolvedOnlyAfterBeingAbsentLongEnough() {
        AlertTracker t = new AlertTracker(2, 100);
        t.update(List.of(issue("a", Severity.CRITICAL)), at(0));
        t.update(List.of(), at(1));
        assertEquals(1, t.recent(10).size(), "one absence is not yet resolved");
        t.update(List.of(), at(2));
        assertEquals(AlertType.RESOLVED, t.recent(10).get(0).type());
        assertEquals(Severity.CRITICAL, t.recent(10).get(0).severity());
    }

    @Test
    void aFlappingIssueKeepsItsAgeAndDoesNotSpamAlerts() {
        AlertTracker t = new AlertTracker(2, 100);
        t.update(List.of(issue("a", Severity.WARNING)), at(0));
        t.update(List.of(), at(1));
        Issue back = t.update(List.of(issue("a", Severity.WARNING)), at(2)).get(0);
        assertEquals(at(0), back.firstSeen());
        assertEquals(1, t.recent(10).size());
        // and the absence counter restarted: one more gap still doesn't resolve it
        t.update(List.of(), at(3));
        assertEquals(1, t.recent(10).size());
    }

    @Test
    void aResolvedIssueThatReturnsIsANewIssueWithAFreshFirstSeen() {
        AlertTracker t = new AlertTracker(1, 100);
        t.update(List.of(issue("a", Severity.WARNING)), at(0));
        t.update(List.of(), at(1));
        Issue again = t.update(List.of(issue("a", Severity.WARNING)), at(10)).get(0);
        assertEquals(at(10), again.firstSeen());
        assertEquals(List.of(AlertType.NEW, AlertType.RESOLVED, AlertType.NEW),
                List.of(t.recent(10).get(2).type(), t.recent(10).get(1).type(), t.recent(10).get(0).type()));
    }

    @Test
    void resolvedUsesTheWorstSeverityTheIssueReached() {
        AlertTracker t = new AlertTracker(1, 100);
        t.update(List.of(issue("a", Severity.CRITICAL)), at(0));
        t.update(List.of(issue("a", Severity.WARNING)), at(1));
        t.update(List.of(), at(2));
        assertEquals(Severity.CRITICAL, t.recent(1).get(0).severity());
    }

    @Test
    void theLogIsBoundedAndNewestFirst() {
        AlertTracker t = new AlertTracker(1, 10);
        for (int k = 0; k < 50; k++) {
            t.update(List.of(issue("k" + k, Severity.WARNING)), at(k));
        }
        List<AlertEvent> all = t.recent(1000);
        assertEquals(10, all.size());
        assertEquals("k49", all.get(0).issueKey());
        assertEquals(3, t.recent(3).size());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AlertTracker(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new AlertTracker(1, 0));
    }
}
