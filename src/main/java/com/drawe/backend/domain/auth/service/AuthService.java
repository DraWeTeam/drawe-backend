package com.drawe.backend.domain.auth.service;

import com.drawe.backend.domain.RefreshToken;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.auth.dto.*;
import com.drawe.backend.domain.auth.repository.UserRepository;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import com.drawe.backend.global.security.JwtProvider;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

  private final UserRepository userRepository;
  private final RefreshTokenService refreshTokenService;
  private final PasswordEncoder passwordEncoder;
  private final JwtProvider jwtProvider;

  @Transactional
  public SignupResponse signup(SignupRequest request) {
    if (userRepository.existsByEmail(request.getEmail())) {
      throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
    }
    if (userRepository.existsByNickname(request.getNickname())) {
      throw new CustomException(ErrorCode.NICKNAME_ALREADY_EXISTS);
    }

    User user =
        User.builder()
            .email(request.getEmail())
            .password(passwordEncoder.encode(request.getPassword()))
            .nickname(request.getNickname())
            .build();

    userRepository.save(user);

    return SignupResponse.builder()
        .userId(user.getId())
        .email(user.getEmail())
        .nickname(user.getNickname())
        .build();
  }

  @Transactional
  public AuthResponse login(LoginRequest request) {
    User user =
        userRepository
            .findByEmail(request.getEmail())
            .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));

    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
      throw new CustomException(ErrorCode.UNAUTHORIZED);
    }

    return issueTokens(user);
  }

  @Transactional
  public RefreshTokenResponse refresh(RefreshTokenRequest request) {
    String refreshToken = request.getRefreshToken();

    if (refreshToken == null || refreshToken.isBlank()) {
      throw new CustomException(ErrorCode.INVALID_TOKEN);
    }

    if (!jwtProvider.validateToken(refreshToken)) {
      throw new CustomException(ErrorCode.INVALID_TOKEN);
    }

    RefreshToken savedToken = refreshTokenService.findByToken(refreshToken);

    if (savedToken.getExpiryAt().isBefore(Instant.now())) {
      refreshTokenService.deleteByToken(refreshToken);
      throw new CustomException(ErrorCode.INVALID_TOKEN);
    }

    User user = savedToken.getUser();
    if (user == null) {
      throw new CustomException(ErrorCode.USER_NOT_FOUND);
    }

    // rotation
    refreshTokenService.deleteByToken(refreshToken);

    String newAccessToken =
        jwtProvider.createAccessToken(user.getId(), user.getEmail(), user.getNickname());
    String newRefreshToken = jwtProvider.createRefreshToken(user.getId());
    Instant newRefreshExpiry = jwtProvider.getExpiration(newRefreshToken).toInstant();

    refreshTokenService.rotate(user, refreshToken, newRefreshToken, newRefreshExpiry);

    return RefreshTokenResponse.builder()
        .accessToken(newAccessToken)
        .refreshToken(newRefreshToken)
        .build();
  }

  @Transactional
  public void logout(RefreshTokenRequest request) {
    String refreshToken = request.getRefreshToken();

    if (refreshToken == null || refreshToken.isBlank()) {
      throw new CustomException(ErrorCode.INVALID_TOKEN);
    }

    refreshTokenService.deleteByToken(refreshToken);
  }

  @Transactional
  public void logoutAll(String authorizationHeader) {
    String accessToken = extractBearerToken(authorizationHeader);

    if (!jwtProvider.validateToken(accessToken)) {
      throw new CustomException(ErrorCode.INVALID_TOKEN);
    }

    Long userId = jwtProvider.getUserIdFromToken(accessToken);
    if (userId == null) {
      throw new CustomException(ErrorCode.UNAUTHORIZED);
    }

    refreshTokenService.deleteAllByUserId(userId);
  }

  public CheckAvailabilityResponse checkEmail(String email) {
    if (userRepository.existsByEmail(email)) {
      throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
    }
    return new CheckAvailabilityResponse(true);
  }

  public CheckAvailabilityResponse checkNickname(String nickname) {
    if (userRepository.existsByNickname(nickname)) {
      throw new CustomException(ErrorCode.NICKNAME_ALREADY_EXISTS);
    }
    return new CheckAvailabilityResponse(true);
  }

  public void checkPassword(Long userId, PasswordCheckRequest request) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));

    if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
      throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
  }

  private AuthResponse issueTokens(User user) {
    String accessToken =
        jwtProvider.createAccessToken(user.getId(), user.getEmail(), user.getNickname());
    String refreshToken = jwtProvider.createRefreshToken(user.getId());

    Instant refreshExpiry = jwtProvider.getExpiration(refreshToken).toInstant();
    refreshTokenService.save(user, refreshToken, refreshExpiry);

    return AuthResponse.builder()
        .userId(user.getId())
        .accessToken(accessToken)
        .refreshToken(refreshToken)
        .email(user.getEmail())
        .nickname(user.getNickname())
        .provider(user.getProvider())
        .build();
  }

  private String extractBearerToken(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      throw new CustomException(ErrorCode.INVALID_TOKEN);
    }
    return authorizationHeader.substring(7);
  }
}
