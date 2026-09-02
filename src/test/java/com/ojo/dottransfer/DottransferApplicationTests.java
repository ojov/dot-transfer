package com.ojo.dottransfer;

import com.ojo.dottransfer.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application against a real database.
 *
 * <p>More than a formality now that Flyway owns the schema and Hibernate runs with
 * {@code ddl-auto: validate}: this fails if a migration and the entities have drifted apart, which
 * makes it the cheapest guard against a mismatch reaching a deployment.
 */
@SpringBootTest
@ActiveProfiles("test")
class DottransferApplicationTests extends PostgresTestBase {

    @Test
    void contextLoadsAndTheSchemaMatchesTheEntities() {
    }
}
