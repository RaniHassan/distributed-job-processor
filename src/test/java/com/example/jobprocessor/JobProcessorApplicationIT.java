package com.example.jobprocessor;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@ApiIntegrationTest
class JobProcessorApplicationIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private JobProcessorProperties properties;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void connectsToPostgreSql() {
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);

        assertThat(version).startsWith("PostgreSQL 17");
    }

    @Test
    void databaseSessionUsesUtc() {
        String timeZone = jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class);

        assertThat(timeZone).isIn("UTC", "Etc/UTC");
    }

    @Test
    void flywayIsConfiguredWithMigrationLocation() {
        assertThat(flyway.getConfiguration().getLocations())
                .extracting(Object::toString)
                .containsExactly("classpath:db/migration");
        assertThat(flyway.info()).isNotNull();
    }

    @Test
    void testProfileUsesShortTimings() {
        assertThat(properties.lease().duration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.simulation().maxDuration()).isLessThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(properties.simulation().failureInjectionEnabled()).isTrue();
    }

    @Test
    void healthEndpointsReportUp() {
        for (String path : new String[] {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"}) {
            ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).as(path).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void healthDoesNotExposeDetails() {
        String body = restTemplate.getForObject("/actuator/health", String.class);

        assertThat(body).doesNotContain("components", "PostgreSQL", "db");
    }

    /** Health probes are public; everything else under /actuator needs a token (exposure is checked in AuthApiIT). */
    @Test
    void otherManagementEndpointsRequireAuthentication() {
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
