package com.drawe.backend.repository;

import com.drawe.backend.domain.RefreshToken;
import com.drawe.backend.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    List<RefreshToken> findAllByUser(User user);

    void deleteByToken(String token);

    void deleteAllByUser(User user);
}