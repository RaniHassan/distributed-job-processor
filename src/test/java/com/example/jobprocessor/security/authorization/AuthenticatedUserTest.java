package com.example.jobprocessor.security.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.user.domain.Role;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class AuthenticatedUserTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void readsIdentityFromClaims() {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt(b -> b.subject(id.toString())
                .claim("username", "alice").claim("role", "ADMIN")));

        assertThat(user).isEqualTo(new AuthenticatedUser(id, "alice", Role.ADMIN));
    }

    @Test
    void rejectsNonUuidSubject() {
        assertInvalid(jwt(b -> b.subject("alice").claim("username", "alice").claim("role", "USER")));
    }

    @Test
    void rejectsMissingSubject() {
        assertInvalid(jwt(b -> b.claim("username", "alice").claim("role", "USER")));
    }

    @Test
    void rejectsUnknownRole() {
        assertInvalid(jwt(b -> b.subject(id.toString()).claim("username", "alice").claim("role", "ROOT")));
    }

    @Test
    void rejectsMissingRole() {
        assertInvalid(jwt(b -> b.subject(id.toString()).claim("username", "alice")));
    }

    @Test
    void rejectsBlankUsername() {
        assertInvalid(jwt(b -> b.subject(id.toString()).claim("username", " ").claim("role", "USER")));
    }

    private static void assertInvalid(Jwt jwt) {
        assertThatThrownBy(() -> AuthenticatedUser.fromJwt(jwt)).isInstanceOf(InvalidBearerTokenException.class);
    }

    private static Jwt jwt(Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "HS256").claim("iss", "test");
        claims.accept(builder);
        return builder.build();
    }
}
