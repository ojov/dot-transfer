package com.ojo.dottransfer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables the {@code @CreatedDate}/{@code @LastModifiedDate} population on {@code BaseEntity}.
 *
 * <p>Deliberately its own class rather than an annotation on the application class. Sitting on
 * {@code @SpringBootApplication} it is pulled into every sliced test that names the application as
 * its configuration source - including web slices, which have no JPA metamodel and so fail to start
 * with "JPA metamodel must not be empty". Isolated here, a slice picks it up only when it actually
 * loads JPA.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
