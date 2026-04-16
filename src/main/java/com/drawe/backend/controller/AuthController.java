package com.drawe.backend.controller;

import com.drawe.backend.dto.TokenRefreshRequest;
import com.drawe.backend.dto.TokenRefreshResponse;
import com.drawe.backend.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    @Value("${spring.security.oauth2.client.registration.google.client-id}")
    private String clientId;

    @Value("${spring.security.oauth2.client.registration.google.redirect-uri}")
    private String redirectUri;

    @GetMapping("/google")
    public Map<String, String> getGoogleLoginUrl() {
        String scope = URLEncoder.encode("openid email profile", StandardCharsets.UTF_8);

        String googleLoginUrl =
                "https://accounts.google.com/o/oauth2/v2/auth"
                        + "?client_id=" + clientId
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                        + "&response_type=code"
                        + "&scope=" + scope;

        return Map.of("url", googleLoginUrl);
    }


    @PostMapping("/refresh")
    public TokenRefreshResponse refresh(@RequestBody TokenRefreshRequest request) {
        return authService.refresh(request.getRefreshToken());
    }
}