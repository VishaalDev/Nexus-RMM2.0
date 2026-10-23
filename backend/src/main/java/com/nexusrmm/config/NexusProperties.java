package com.nexusrmm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Central configuration for Nexus-RMM. All tunables the collection,
 * analysis, alert and sync engines depend on are bound here from
 * application.yml (prefix: nexus) rather than hardcoded in services.
 */
@ConfigurationProperties(prefix = "nexus")
public class NexusProperties {

    private final Collection collection = new Collection();
    private final Database database = new Database();
    private final History history = new History();
    private final Hardware hardware = new Hardware();
    private final Events events = new Events();

    public Collection getCollection() {
        return collection;
    }

    public Database getDatabase() {
        return database;
    }

    public History getHistory() {
        return history;
    }

    public Hardware getHardware() {
        return hardware;
    }

    public Events getEvents() {
        return events;
    }

    public static class Collection {
        /** How often (seconds) the collector polls all registered metric providers. */
        private int intervalSeconds = 10;

        public int getIntervalSeconds() {
            return intervalSeconds;
        }

        public void setIntervalSeconds(int intervalSeconds) {
            this.intervalSeconds = intervalSeconds;
        }
    }

    public static class Database {
        private String path = "./data/nexus-rmm.db";

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public static class History {
        private String defaultWindow = "PT1H";
        /** Hard ceiling on points returned by one history query, regardless of window size. */
        private int maxPoints = 5000;
        /** How far back "current value" is allowed to look for a still-fresh sample per instance. */
        private int currentValueLookbackMultiplier = 3;

        public String getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(String defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public int getMaxPoints() {
            return maxPoints;
        }

        public void setMaxPoints(int maxPoints) {
            this.maxPoints = maxPoints;
        }

        public int getCurrentValueLookbackMultiplier() {
            return currentValueLookbackMultiplier;
        }

        public void setCurrentValueLookbackMultiplier(int currentValueLookbackMultiplier) {
            this.currentValueLookbackMultiplier = currentValueLookbackMultiplier;
        }
    }

    public static class Hardware {
        private final Pawnio pawnio = new Pawnio();

        public Pawnio getPawnio() {
            return pawnio;
        }

        public static class Pawnio {
            /**
             * Optional path to a compiled PawnIO MSR module (e.g. IntelMSR.bin).
             * When unset, CpuTemperatureProvider only attempts the OSHI sensor
             * path and reports PawnIO as UNSUPPORTED/not-configured.
             */
            private String modulePath;

            public String getModulePath() {
                return modulePath;
            }

            public void setModulePath(String modulePath) {
                this.modulePath = modulePath;
            }
        }
    }

    public static class Events {
        private boolean enabled = true;
        private int pollIntervalSeconds = 60;
        /** How many hours back to look on each poll for new System-log entries. */
        private int lookbackHours = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPollIntervalSeconds() {
            return pollIntervalSeconds;
        }

        public void setPollIntervalSeconds(int pollIntervalSeconds) {
            this.pollIntervalSeconds = pollIntervalSeconds;
        }

        public int getLookbackHours() {
            return lookbackHours;
        }

        public void setLookbackHours(int lookbackHours) {
            this.lookbackHours = lookbackHours;
        }
    }
}
