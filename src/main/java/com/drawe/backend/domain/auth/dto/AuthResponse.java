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
  private String email;
  private String nickname;
  private String provider;
}
