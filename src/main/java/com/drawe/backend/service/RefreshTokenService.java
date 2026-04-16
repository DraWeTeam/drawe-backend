package com.drawe.backend.service;

import com.drawe.backend.domain.RefreshToken;
import com.drawe.backend.domain.User;
import com.drawe.backend.repository.RefreshTokenRepository;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional
    public void save(User user, String refreshToken, Instant expiryAt) {
        refreshTokenRepository.save(
                RefreshToken.builder()
                        .user(user)
                        .token(refreshToken)
                        .expiryAt(expiryAt)
                        .build()
        );
    }

    @Transactional(readOnly = true)
    public RefreshToken findByToken(String token) {
        return refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("유효하지 않은 refresh token입니다."));
    }

    @Transactional(readOnly = true)
    public List<RefreshToken> findAllByUser(User user) {
        return refreshTokenRepository.findAllByUser(user);
    }

    @Transactional
    public void deleteByToken(String token) {
        refreshTokenRepository.deleteByToken(token);
    }

    @Transactional
    public void deleteAllByUser(User user) {
        refreshTokenRepository.deleteAllByUser(user);
    }

    @Transactional
    public void rotate(User user, String oldToken, String newToken, Instant newExpiryAt) {
        refreshTokenRepository.deleteByToken(oldToken);

        refreshTokenRepository.save(
                RefreshToken.builder()
                        .user(user)
                        .token(newToken)
                        .expiryAt(newExpiryAt)
                        .build()
        );
    }

}
