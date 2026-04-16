package com.drawe.backend.domain.auth.controller;

import com.drawe.backend.domain.auth.dto.*;
import com.drawe.backend.domain.auth.service.AuthService;
import com.drawe.backend.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
        return ApiResponse.success(authService.signup(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/reissue")
    public ApiResponse<TokenResponse> reissue(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.reissue(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ApiResponse.success();
    }

    @GetMapping("/check-email")
    public ApiResponse<CheckAvailabilityResponse> checkEmail(
            @RequestParam @NotBlank @Email String email) {
        return ApiResponse.success(authService.checkEmail(email));
    }

    @GetMapping("/check-nickname")
    public ApiResponse<CheckAvailabilityResponse> checkNickname(
            @RequestParam @NotBlank String nickname) {
        return ApiResponse.success(authService.checkNickname(nickname));
    }

    @PostMapping("/check-password")
    public ApiResponse<Void> checkPassword(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody PasswordCheckRequest request) {
        authService.checkPassword(userId, request);
        return ApiResponse.success();
    }
}
