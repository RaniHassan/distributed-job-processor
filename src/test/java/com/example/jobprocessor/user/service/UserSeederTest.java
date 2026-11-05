package com.example.jobprocessor.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.user.domain.User;
import com.example.jobprocessor.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserSeederTest {

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Test
    void createsMissingUsersWithHashedPasswords() {
        seeder(new SeedProperties.SeedUser("alice", "alice-pw", Role.USER)).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("alice");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
        assertThat(saved.getValue().getPasswordHash()).startsWith("$2a$").doesNotContain("alice-pw");
        assertThat(passwordEncoder.matches("alice-pw", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void leavesExistingUsersUntouched() {
        when(users.existsByUsername("alice")).thenReturn(true);

        seeder(new SeedProperties.SeedUser("alice", "new-pw", Role.ADMIN)).run(null);

        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void toleratesAnotherInstanceCreatingTheSameUser() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_users_username"));

        seeder(new SeedProperties.SeedUser("alice", "pw", Role.USER),
                new SeedProperties.SeedUser("bob", "pw", Role.USER)).run(null);

        verify(users, org.mockito.Mockito.times(2)).saveAndFlush(any());
    }

    @Test
    void seedPasswordsAreNeverPrinted() {
        assertThat(new SeedProperties.SeedUser("alice", "top-secret", Role.USER).toString()).doesNotContain("top-secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "default"})
    void seedingIsNotAvailableOutsideDevAndTest(String profile) {
        contextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(UserSeeder.class);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test"})
    void seedingIsAvailableInDevAndTest(String profile) {
        contextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("jobprocessor.seed.users[0].username=alice",
                        "jobprocessor.seed.users[0].password=pw", "jobprocessor.seed.users[0].role=USER")
                .run(context -> assertThat(context).hasSingleBean(UserSeeder.class));
    }

    @Test
    void unsetPasswordVariableFailsStartup() {
        contextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("jobprocessor.seed.users[0].username=alice",
                        "jobprocessor.seed.users[0].password=${SEED_USER_PASSWORD}", "jobprocessor.seed.users[0].role=USER")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("SEED_*_PASSWORD");
                });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(UserSeedConfiguration.class)
                .withBean(UserRepository.class, () -> users)
                .withBean(PasswordEncoder.class, () -> passwordEncoder);
    }

    private UserSeeder seeder(SeedProperties.SeedUser... seedUsers) {
        return new UserSeeder(users, passwordEncoder, new SeedProperties(List.of(seedUsers)));
    }
}
