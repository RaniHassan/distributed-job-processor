package com.example.jobprocessor.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobprocessor.PostgresRepositoryTest;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresRepositoryTest
class UserRepositoryIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void savesAndFindsUserByUsername() {
        User saved = userRepository.saveAndFlush(new User("carol", "$2a$10$hash", Role.ADMIN));
        entityManager.clear();

        User loaded = userRepository.findByUsername("carol").orElseThrow();

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getRole()).isEqualTo(Role.ADMIN);
        assertThat(loaded.isEnabled()).isTrue();
        assertThat(loaded.getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(userRepository.existsByUsername("carol")).isTrue();
        assertThat(userRepository.findByUsername("nobody")).isEmpty();
    }
}
