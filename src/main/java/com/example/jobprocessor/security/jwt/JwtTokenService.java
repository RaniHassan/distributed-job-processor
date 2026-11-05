package com.example.jobprocessor.security.jwt;

import com.example.jobprocessor.common.config.JobProcessorProperties;
import com.example.jobprocessor.security.authorization.AuthenticatedUser;
import com.example.jobprocessor.user.domain.User;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues access tokens. Tokens carry identity and role only; permissions are derived on the server
 * for each request. Tokens are stateless and remain valid until expiry, even if the user is
 * disabled in the meantime (bounded by the short TTL).
 */
@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;

    public JwtTokenService(JwtEncoder encoder, JobProcessorProperties properties) {
        this.encoder = encoder;
        this.issuer = properties.security().jwt().issuer();
        this.ttl = properties.security().jwt().accessTokenTtl();
    }

    public AccessToken issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim(AuthenticatedUser.USERNAME_CLAIM, user.getUsername())
                .claim(AuthenticatedUser.ROLE_CLAIM, user.getRole().name())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, ttl);
    }

    public record AccessToken(String value, Duration expiresIn) {
    }
}
