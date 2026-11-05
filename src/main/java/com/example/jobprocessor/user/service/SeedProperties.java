package com.example.jobprocessor.user.service;

import com.example.jobprocessor.user.domain.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Users to create at startup in the dev and test profiles. */
@Validated
@ConfigurationProperties(prefix = "jobprocessor.seed")
public record SeedProperties(List<@Valid SeedUser> users) {

    public SeedProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    public record SeedUser(@NotBlank String username, @NotBlank String password, @NotNull Role role) {

        /** An unset environment variable must not silently become a literal "${...}" password. */
        @AssertTrue(message = "seed user password is not configured (set the SEED_*_PASSWORD environment variable)")
        public boolean isPasswordConfigured() {
            return password == null || !password.contains("${");
        }

        @Override
        public String toString() {
            return "SeedUser[username=" + username + ", password=******, role=" + role + "]";
        }
    }
}
