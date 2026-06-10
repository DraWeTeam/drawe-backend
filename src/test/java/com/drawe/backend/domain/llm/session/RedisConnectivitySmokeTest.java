package com.drawe.backend.domain.llm.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.DataRedisTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 연결성 smoke test (슬라이스 테스트).
 *
 * <p>{@code @DataRedisTest} 로 Redis 관련 자동설정만 로드. DB·Web·다른 bean 의존성 무시.
 *
 * <p>실행 전 조건:
 * <ul>
 *   <li>로컬 Valkey/Redis 기동 ({@code docker compose up -d drawe_redis})</li>
 *   <li>application.properties 의 비번 ↔ .env REDIS_PASSWORD 일치</li>
 * </ul>
 */
@DataRedisTest
class RedisConnectivitySmokeTest {

    private static final String TEST_KEY = "drawe:smoke-test:connectivity";

    @Autowired private StringRedisTemplate redisTemplate;

    @AfterEach
    void cleanup() {
        redisTemplate.delete(TEST_KEY);
    }

    @Test
    @DisplayName("Redis 연결 — set/get 라운드트립")
    void redisConnectionWorks() {
        String value = "phase6-ready-" + System.currentTimeMillis();

        redisTemplate.opsForValue().set(TEST_KEY, value);
        String retrieved = redisTemplate.opsForValue().get(TEST_KEY);

        assertThat(retrieved).isEqualTo(value);
    }

    @Test
    @DisplayName("Redis 연결 — 키 삭제")
    void redisDeleteWorks() {
        redisTemplate.opsForValue().set(TEST_KEY, "temp");
        Boolean deleted = redisTemplate.delete(TEST_KEY);

        assertThat(deleted).isTrue();
        assertThat(redisTemplate.opsForValue().get(TEST_KEY)).isNull();
    }
}