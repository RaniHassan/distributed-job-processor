package com.example.jobprocessor.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.jobprocessor.security.jwt.JwtTokenService;
import com.example.jobprocessor.security.jwt.JwtTokenService.AccessToken;
import com.example.jobprocessor.user.domain.Role;
import com.example.jobprocessor.user.domain.User;
import com.example.jobprocessor.user.repository.UserRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthenticationServiceTest {

    @Mock
    private UserRepository users;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenService tokenService;

    private AuthenticationService service;
    private final User alice = new User("alice", "alice-hash", Role.USER);

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("dummy-hash");
        service = new AuthenticationService(users, passwordEncoder, tokenService);
    }

    @Test
    void issuesTokenForValidCredentials() {
        AccessToken token = new AccessToken("jwt", Duration.ofMinutes(15));
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(passwordEncoder.matches("secret", "alice-hash")).thenReturn(true);
        when(tokenService.issue(alice)).thenReturn(token);

        assertThat(service.login("alice", "secret")).isEqualTo(token);
    }

    @Test
    void rejectsWrongPassword() {
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(passwordEncoder.matches("wrong", "alice-hash")).thenReturn(false);

        assertThatThrownBy(() -> service.login("alice", "wrong")).isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(tokenService);
    }

    /** The hash comparison still runs, so timing does not reveal which usernames exist. */
    @Test
    void unknownUserStillPerformsAPasswordCheck() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "secret")).isInstanceOf(InvalidCredentialsException.class);
        verify(passwordEncoder).matches(eq("secret"), eq("dummy-hash"));
        verifyNoInteractions(tokenService);
    }

    @Test
    void rejectsDisabledAccountEvenWithCorrectPassword() {
        ReflectionTestUtils.setField(alice, "enabled", false);
        when(users.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(passwordEncoder.matches("secret", "alice-hash")).thenReturn(true);

        assertThatThrownBy(() -> service.login("alice", "secret")).isInstanceOf(InvalidCredentialsException.class);
        verify(tokenService, never()).issue(any());
    }

    @Test
    void rejectsPasswordsBcryptWouldTruncate() {
        String tooLong = "p".repeat(73);

        assertThatThrownBy(() -> service.login("alice", tooLong)).isInstanceOf(InvalidCredentialsException.class);
        verifyNoInteractions(users, tokenService);
    }

    @Test
    void allFailuresLookIdentical() {
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.login("ghost", "x"))
                .hasMessage("Invalid username or password")
                .satisfies(e -> assertThat(((InvalidCredentialsException) e).errorCode()).isEqualTo("INVALID_CREDENTIALS"));
    }
}
