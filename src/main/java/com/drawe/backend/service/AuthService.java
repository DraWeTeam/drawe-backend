package com.drawe.backend.service;

import com.drawe.backend.domain.RefreshToken;
import com.drawe.backend.domain.User;
import com.drawe.backend.dto.TokenRefreshResponse;
import com.drawe.backend.repository.UserRepository;
import com.drawe.backend.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public TokenRefreshResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("refresh token이 비어 있습니다.");
        }

        if (!jwtProvider.validateToken(refreshToken)) {
            throw new IllegalArgumentException("refresh token이 유효하지 않습니다.");
        }

        RefreshToken savedToken = refreshTokenService.findByToken(refreshToken);

        if (savedToken.getExpiryAt().isBefore(Instant.now())) {
            refreshTokenService.deleteByToken(refreshToken);
            throw new IllegalArgumentException("refresh token이 만료되었습니다.");
        }

        User user = savedToken.getUser();

        String newAccessToken = jwtProvider.createAccessToken(user.getId(), user.getEmail());
        String newRefreshToken = jwtProvider.createRefreshToken(user.getId());
        Instant newRefreshExpiry = jwtProvider.getExpiration(newRefreshToken).toInstant();

        refreshTokenService.rotate(user, refreshToken, newRefreshToken, newRefreshExpiry);

        return new TokenRefreshResponse(newAccessToken, newRefreshToken);
    }
}