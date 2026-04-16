package com.drawe.backend.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token", nullable = false, length = 1000)
    private String token;

    @Column(name = "expiry_at", nullable = false)
    private Instant expiryAt;

    public void updateToken(String token, Instant expiryAt) {
        this.token = token;
        this.expiryAt = expiryAt;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiryAt);
    }
}
