package com.example.jobprocessor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Full application on a random port against Testcontainers PostgreSQL, with the real security
 * filter chain. All classes using it share one Spring context (and one database container).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
// Tests otherwise get a plain in-memory registry; this keeps the production Prometheus registry and endpoint.
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestUsers.class, TestRows.class})
public @interface ApiIntegrationTest {
}
