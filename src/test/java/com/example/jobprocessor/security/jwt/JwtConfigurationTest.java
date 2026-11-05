package com.example.jobprocessor.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.jobprocessor.TestProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.common.config.JobProcessorProperties.JwtSettings;
import com.example.jobprocessor.common.config.JobProcessorProperties.Security;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.user.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

class JwtConfigurationTest {

    private final JobProcessorProperties properties = TestProperties.create(false);
    private final JwtConfiguration configuration = new JwtConfiguration();
    private final JwtEncoder encoder = configuration.jwtEncoder(properties);
    private final JwtDecoder decoder = configuration.jwtDecoder(properties);

    @Test
    void issuedTokenRoundTripsWithIdentityClaims() {
        User user = new User("alice", "hash", Role.USER);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(user, "id", id);

        JwtTokenService.AccessToken token = new JwtTokenService(encoder, properties).issue(user);
        Jwt jwt = decoder.decode(token.value());

        assertThat(token.expiresIn()).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getSubject()).isEqualTo(id.toString());
        assertThat(jwt.getClaimAsString("username")).isEqualTo("alice");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        // getIssuer() would require the issuer to be a URL; ours is a plain string identifier.
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TestProperties.JWT_ISSUER);
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void rejectsExpiredToken() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        String token = sign(encoder, claims(TestProperties.JWT_ISSUER, past, past.plus(Duration.ofMinutes(15))));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        String token = sign(encoder, claims("someone-else", Instant.now(), Instant.now().plusSeconds(600)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class).hasMessageContaining("iss");
    }

    @Test
    void rejectsTokenWithoutExpiry() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(TestProperties.JWT_ISSUER).issuedAt(Instant.now()).subject(UUID.randomUUID().toString()).build();

        assertThatThrownBy(() -> decoder.decode(sign(encoder, claims))).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherKey() {
        JobProcessorProperties other = withSecret("another-signing-secret-that-is-long-enough-123");
        JwtEncoder otherEncoder = configuration.jwtEncoder(other);
        String token = sign(otherEncoder, claims(TestProperties.JWT_ISSUER, Instant.now(), Instant.now().plusSeconds(600)));

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void rejectsUnsignedToken() {
        String unsigned = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4IiwiaXNzIjoidGVzdC1pc3N1ZXIifQ.";

        assertThatThrownBy(() -> decoder.decode(unsigned)).isInstanceOf(JwtException.class);
    }

    private static JwtClaimsSet claims(String issuer, Instant issuedAt, Instant expiresAt) {
        return JwtClaimsSet.builder()
                .issuer(issuer).issuedAt(issuedAt).expiresAt(expiresAt)
                .subject(UUID.randomUUID().toString()).claim("username", "alice").claim("role", "USER")
                .build();
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private JobProcessorProperties withSecret(String secret) {
        JobProcessorProperties p = properties;
        return new JobProcessorProperties(p.worker(), p.dispatcher(), p.lease(), p.recovery(), p.retry(), p.simulation(),
                p.shutdown(), p.idempotency(), p.api(),
                new Security(new JwtSettings(secret, TestProperties.JWT_ISSUER, Duration.ofMinutes(15))));
    }
}
