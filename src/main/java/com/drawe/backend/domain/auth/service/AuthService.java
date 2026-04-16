package com.drawe.backend.domain.auth.service;

import com.drawe.backend.domain.RefreshToken;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.auth.dto.*;
import com.drawe.backend.domain.repository.RefreshTokenRepository;
import com.drawe.backend.domain.repository.UserRepository;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import com.drawe.backend.global.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        if (userRepository.existsByNickname(request.getNickname())) {
            throw new CustomException(ErrorCode.NICKNAME_ALREADY_EXISTS);
        }

        User user = User.builder()
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
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }

        return issueTokens(user);
    }

    @Transactional
    public TokenResponse reissue(RefreshTokenRequest request) {
        String token = request.getRefreshToken();

        if (!jwtTokenProvider.validateToken(token)) {
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        RefreshToken stored = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_TOKEN));

        if (stored.isExpired()) {
            refreshTokenRepository.deleteByToken(token);
            throw new CustomException(ErrorCode.INVALID_TOKEN);
        }

        User user = stored.getUser();
        if (user == null) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        refreshTokenRepository.deleteByToken(token);

        String newAccessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getNickname());
        String newRefreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        saveRefreshToken(user, newRefreshToken);

        return TokenResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.deleteByUserId(userId);
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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtTokenProvider.createAccessToken(user.getId(), user.getEmail(), user.getNickname());
        String refreshToken = jwtTokenProvider.createRefreshToken(user.getId());
        saveRefreshToken(user, refreshToken);

        return AuthResponse.builder()
                .userId(user.getId())
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .nickname(user.getNickname())
                .provider(user.getProvider())
                .build();
    }

    private void saveRefreshToken(User user, String token) {
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .token(token)
                .expiryAt(Instant.now().plus(7, ChronoUnit.DAYS))
                .build());
    }
}
