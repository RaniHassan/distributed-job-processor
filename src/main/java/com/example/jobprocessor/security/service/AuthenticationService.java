package com.example.jobprocessor.security.service;

import com.example.jobprocessor.security.jwt.JwtTokenService;
import com.example.jobprocessor.security.jwt.JwtTokenService.AccessToken;
import com.example.jobprocessor.user.domain.User;
import com.example.jobprocessor.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Verifies credentials and issues access tokens. */
@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    /** BCrypt only uses the first 72 bytes; longer inputs are rejected rather than truncated. */
    static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokenService;
    // Compared against when the user does not exist, so response time does not reveal valid usernames.
    private final String unknownUserHash;

    public AuthenticationService(UserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.unknownUserHash = passwordEncoder.encode("unknown-user-timing-equalizer");
    }

    public AccessToken login(String username, String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
            throw rejected(username, "password too long");
        }
        Optional<User> user = users.findByUsername(username);
        boolean passwordMatches = passwordEncoder.matches(password, user.map(User::getPasswordHash).orElse(unknownUserHash));
        if (user.isEmpty() || !passwordMatches) {
            throw rejected(username, "bad credentials");
        }
        if (!user.get().isEnabled()) {
            throw rejected(username, "account disabled");
        }
        log.info("User logged in: userId={}", user.get().getId());
        return tokenService.issue(user.get());
    }

    private static InvalidCredentialsException rejected(String username, String reason) {
        log.warn("Login failed: username={} reason={}", sanitize(username), reason);
        return new InvalidCredentialsException();
    }

    private static String sanitize(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}]", "_");
        return clean.length() > 100 ? clean.substring(0, 100) + "..." : clean;
    }
}
