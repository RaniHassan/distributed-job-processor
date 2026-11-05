package com.example.jobprocessor.user.service;

import com.example.jobprocessor.user.domain.User;
import com.example.jobprocessor.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates missing seed users with BCrypt-hashed passwords. Create-only: existing users are left
 * untouched. Safe when several instances start at once, since the unique username constraint
 * decides which instance creates a user.
 */
public class UserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SeedProperties properties;

    public UserSeeder(UserRepository users, PasswordEncoder passwordEncoder, SeedProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (SeedProperties.SeedUser seed : properties.users()) {
            if (users.existsByUsername(seed.username())) {
                continue;
            }
            try {
                users.saveAndFlush(new User(seed.username(), passwordEncoder.encode(seed.password()), seed.role()));
                log.info("Seeded user username={} role={}", seed.username(), seed.role());
            } catch (DataIntegrityViolationException e) {
                log.info("Seed user username={} was created concurrently by another instance", seed.username());
            }
        }
    }
}
