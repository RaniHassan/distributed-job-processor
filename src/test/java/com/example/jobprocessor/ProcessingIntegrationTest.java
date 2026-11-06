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
 * Like {@link ApiIntegrationTest} but with the Dispatcher and Recovery Manager running, so jobs
 * are claimed, executed, heartbeated and recovered by the real background threads. Uses its own Spring context and database container, so it
 * never processes jobs created by the API tests.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jobprocessor.dispatcher.enabled=true", "jobprocessor.recovery.enabled=true"})
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestUsers.class, TestRows.class})
public @interface ProcessingIntegrationTest {
}
