package com.ojo.dottransfer.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Scheduling for a service that runs as several instances.
 *
 * <p>Every pod owns the same cron triggers, so without coordination a three-pod deployment would run
 * the commission job three times and compute commission three times over. ShedLock turns the shared
 * database into the arbiter: the first pod to insert its row into {@code shedlock} runs the job, the
 * others find the row and skip. {@code defaultLockAtMostFor} is the crash guard - if the holder dies
 * without releasing, the lock expires and the next scheduled run can proceed.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${dot.jobs.lock-at-most-for}")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        // Lock timing is read from the database clock, not each pod's clock. Pods
                        // drift; if one believed a lock had expired while the holder was still
                        // working, both would run the job.
                        .usingDbTime()
                        .build());
    }

    /**
     * Injected rather than called statically so "now" is a collaborator the tests can pin, instead
     * of something that quietly depends on the wall clock of whatever machine is running.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
