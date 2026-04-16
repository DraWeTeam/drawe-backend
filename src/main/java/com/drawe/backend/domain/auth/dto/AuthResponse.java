package com.drawe.backend.domain.auth.dto;

import com.drawe.backend.domain.user.entity.AuthProvider;
import com.drawe.backend.domain.user.entity.Role;
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
    private String email;
    private String nickname;
    private AuthProvider provider;
    private Role role;
}
