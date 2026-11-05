package com.example.jobprocessor.user.api;

import com.example.jobprocessor.security.jwt.JwtTokenService.AccessToken;
import com.example.jobprocessor.security.service.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        AccessToken token = authenticationService.login(request.username(), request.password());
        return new LoginResponse(token.value(), "Bearer", token.expiresIn().toSeconds());
    }
}
