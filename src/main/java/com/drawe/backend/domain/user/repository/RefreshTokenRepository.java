package com.drawe.backend.domain.user.repository;

import com.drawe.backend.domain.user.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);

    @Modifying
    @Query("delete from RefreshToken r where r.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from RefreshToken r where r.token = :token")
    void deleteByToken(@Param("token") String token);
}
