package com.example.jobprocessor.security.jwt;

import com.example.jobprocessor.security.authorization.AuthenticatedUser;
import com.example.jobprocessor.security.authorization.RolePermissions;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Turns a validated token into an authentication whose authorities are the role's permissions. */
@Component
public class PermissionJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AuthenticatedUser user = AuthenticatedUser.fromJwt(jwt);
        List<GrantedAuthority> authorities = RolePermissions.of(user.role()).stream()
                .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(permission.name()))
                .toList();
        return new JwtAuthenticationToken(jwt, authorities, user.id().toString());
    }
}
