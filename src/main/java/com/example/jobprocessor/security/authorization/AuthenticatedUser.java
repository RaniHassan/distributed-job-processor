package com.example.jobprocessor.security.authorization;

import com.example.jobprocessor.user.domain.Role;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** The caller of a request, as asserted by a validated access token. */
public record AuthenticatedUser(UUID id, String username, Role role) {

    public static final String USERNAME_CLAIM = "username";
    public static final String ROLE_CLAIM = "role";

    /**
     * Reads the user from token claims. A signed token with malformed claims is treated as an
     * invalid token (401), never as an anonymous or partially privileged caller.
     */
    public static AuthenticatedUser fromJwt(Jwt jwt) {
        try {
            UUID id = UUID.fromString(jwt.getSubject());
            String username = jwt.getClaimAsString(USERNAME_CLAIM);
            Role role = Role.valueOf(jwt.getClaimAsString(ROLE_CLAIM));
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("missing username claim");
            }
            return new AuthenticatedUser(id, username, role);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidBearerTokenException("Access token has invalid claims");
        }
    }
}
