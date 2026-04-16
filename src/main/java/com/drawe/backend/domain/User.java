package com.drawe.backend.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Getter
@Setter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "users",
        indexes = {
        @Index(name = "idx_user_prov_pid", columnList = "provider, provider_id")})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Size(max = 255)
    @NotNull
    @Column(name = "email", nullable = false, unique = true)
    private String email;

    //OAuth 유저는 null
    @Size(max = 255)
    @Column(name = "password")
    private String password;

    @Size(max = 100)
    @NotNull
    @Column(name = "nickname", nullable = false, length = 100)
    private String nickname;

    @Column(length = 500)
    private String picture;

    //null(일반) / google
    @Size(max = 20)
    @Column(name = "provider", length = 20)
    private String provider;

    @Size(max = 100)
    @Column(name = "provider_id", length = 100)
    private String providerId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Builder
    public User(String email, String password, String nickname, String picture,
                String provider, String providerId) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.picture = picture;
        this.provider = provider;
        this.providerId = providerId;
    }
}