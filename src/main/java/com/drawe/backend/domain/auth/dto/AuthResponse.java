package com.drawe.backend.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class AuthResponse {

    private Long userId;
    private String accessToken;
    private String refreshToken;
    private UserDto user;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class UserDto {
        private Long id;
        private String nickname;
        private Boolean onboardingCompleted;
    }
}
