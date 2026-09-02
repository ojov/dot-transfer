package com.ojo.dottransfer.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL for every test that touches the database.
 *
 * <p>Not H2. The Flyway baseline is Postgres-specific - {@code uuid} columns, {@code timestamp(6)
 * with time zone}, check constraints - so H2 would need its own parallel migration set, and the
 * schema under test would stop being the schema that ships. More importantly, the behaviour most
 * worth testing here is {@code SELECT ... FOR UPDATE} under contention, which an in-memory
 * substitute does not reproduce.
 *
 * <p>The container is a static singleton started once and deliberately never stopped: Testcontainers'
 * Ryuk sidecar reaps it when the JVM exits. Declaring it per class instead would start a fresh
 * Postgres for every slice and dominate the suite's runtime.
 */
public abstract class PostgresTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
