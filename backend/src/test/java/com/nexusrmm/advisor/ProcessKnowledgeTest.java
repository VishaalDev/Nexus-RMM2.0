package com.nexusrmm.advisor;

import com.nexusrmm.advisor.process.ProcessKnowledge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessKnowledgeTest {

    @Test
    void namesAreNormalisedCaseAndExtensionInsensitively() {
        assertEquals("chrome", ProcessKnowledge.normalize("Chrome.EXE"));
        assertEquals("memory compression", ProcessKnowledge.normalize("  Memory Compression "));
        assertEquals("", ProcessKnowledge.normalize(null));
        assertTrue(ProcessKnowledge.lookup("CHROME.exe").isPresent());
    }

    @Test
    void unknownProcessesGetNoAnnotation() {
        assertFalse(ProcessKnowledge.lookup("definitely-not-known.exe").isPresent());
    }

    @Test
    void windowsComponentsAreFlaggedSoTheyAreNeverToldToBeKilled() {
        assertTrue(ProcessKnowledge.lookup("svchost.exe").orElseThrow().systemComponent());
        assertTrue(ProcessKnowledge.lookup("MsMpEng.exe").orElseThrow().systemComponent());
        assertFalse(ProcessKnowledge.lookup("chrome.exe").orElseThrow().systemComponent());
    }

    @Test
    void everyEntryHasDescriptionAndAdvice() {
        for (String n : new String[] {"chrome", "msedge", "firefox", "code", "onedrive", "ms-teams", "teams", "slack",
                "discord", "spotify", "docker desktop", "com.docker.backend", "vmmem", "vmmemwsl", "java", "javaw",
                "msmpeng", "searchindexer", "tiworker", "svchost", "system", "memory compression", "dwm", "explorer"}) {
            var e = ProcessKnowledge.lookup(n).orElseThrow();
            assertFalse(e.description().isBlank(), n);
            assertFalse(e.advice().isBlank(), n);
        }
    }
}
