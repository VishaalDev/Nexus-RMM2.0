package com.nexusrmm;

import com.nexusrmm.migration.SchemaMigrationInitializer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Nexus-RMM: A Lightweight Monitoring System with Offline Data Synchronization.
 *
 * Entry point for the backend service. Scheduling is enabled here because
 * the collection engine (Module 1) polls hardware providers on a fixed
 * interval defined in application.yml (nexus.collection.interval-seconds).
 *
 * SchemaMigrationInitializer is registered explicitly so any raw-SQL
 * schema repairs (see com.nexusrmm.migration) run before Hibernate's
 * ddl-auto:update touches the database — SQLite cannot alter certain
 * things (like CHECK constraints) in place, so those fixes have to happen
 * first, via plain JDBC, independent of JPA.
 */
@SpringBootApplication
@EnableScheduling
public class NexusRmmApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(NexusRmmApplication.class);
        app.addInitializers(new SchemaMigrationInitializer());
        app.run(args);
    }
}
