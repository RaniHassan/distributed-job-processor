package com.example.jobprocessor;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A PostgreSQL container that tests can pause and resume. A paused container keeps its TCP port
 * open but never answers, like a database host behind a network partition: the hardest outage
 * for clients, because nothing fails fast without timeouts.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PausablePostgres {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestcontainersConfiguration.POSTGRES_IMAGE);

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> pausablePostgresContainer() {
        return POSTGRES;
    }

    public static void pause() {
        POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    }

    public static void resume() {
        if (Boolean.TRUE.equals(POSTGRES.getDockerClient().inspectContainerCmd(POSTGRES.getContainerId()).exec()
                .getState().getPaused())) {
            POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }
    }
}
