package com.example.jobprocessor.user.service;

import com.example.jobprocessor.user.repository.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

/** User seeding exists only in the dev and test profiles; production has no seed users. */
@Configuration(proxyBeanMethods = false)
@Profile({"dev", "test"})
@EnableConfigurationProperties(SeedProperties.class)
public class UserSeedConfiguration {

    @Bean
    UserSeeder userSeeder(UserRepository users, PasswordEncoder passwordEncoder, SeedProperties properties) {
        return new UserSeeder(users, passwordEncoder, properties);
    }
}
