package com.example.jobprocessor;

import com.example.jobprocessor.security.jwt.JwtTokenService;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.user.domain.User;
import com.example.jobprocessor.user.repository.UserRepository;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Creates users with random credentials and issues real access tokens for them. */
@TestComponent
public class TestUsers {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;

    public TestUsers(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    public TestUser create(Role role) {
        String username = role.name().toLowerCase() + "-" + UUID.randomUUID();
        String password = "pw-" + UUID.randomUUID();
        User user = users.saveAndFlush(new User(username, passwordEncoder.encode(password), role));
        return new TestUser(user, password);
    }

    public String bearer(TestUser user) {
        return "Bearer " + tokenService.issue(user.user()).value();
    }

    public record TestUser(User user, String password) {

        public UUID id() {
            return user.getId();
        }

        public String username() {
            return user.getUsername();
        }
    }
}
