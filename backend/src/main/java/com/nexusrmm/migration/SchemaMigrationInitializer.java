package com.nexusrmm.migration;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs {@link SchemaMigrator} against the configured SQLite database file
 * before the Spring ApplicationContext refreshes — specifically before
 * Hibernate's ddl-auto:update or any repository touches the schema — so a
 * rebuilt table (see V1RebuildMetricSampleWithoutEnumCheckConstraints) is
 * already in place by the time JPA starts using it. Registered explicitly
 * in NexusRmmApplication.main() rather than via spring.factories, so the
 * startup sequence stays visible in one place rather than discovered via
 * classpath scanning.
 */
public class SchemaMigrationInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        String databasePath = environment.getProperty("nexus.database.path", "./data/nexus-rmm.db");
        SchemaMigrator.migrate(databasePath);
    }
}
