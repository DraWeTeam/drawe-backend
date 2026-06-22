package com.drawe.backend.domain.image.service;

import io.jsonwebtoken.io.Decoders;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DB 에 저장된 이미지({@code /images/{id}})를 브라우저 {@code <img>} 태그로 직접 로드할 수 있게 해주는 서명 발급/검증기.
 *
 * <p>배경: {@code /images/{id}} 는 JWT Bearer 헤더 인증 + 소유자 검증을 요구한다. 브라우저의 {@code <img src>} 요청에는
 * Authorization 헤더가 실리지 않으므로 AI 생성 이미지가 401/403 으로 깨진다 (베타 P0-1). 외부 절대 URL 인 Unsplash 시드는 영향 없고 우리
 * DB blob (AI 이미지) 만 깨졌다.
 *
 * <p>해결: 노출 직전에 {@code /images/{id}?exp=<epoch초>&sig=<HMAC-SHA256>} 형태의 단기 서명 URL 을 발급한다. 서빙 엔드포인트는
 * 토큰·소유자 검증 대신 이 서명만 검증하므로 태그 로드가 가능하고, 추천 보드에서 타인의 AI 이미지도 정상 노출된다.
 *
 * <p>서명 시크릿은 {@code jwt.secret} (BASE64) 을 재사용한다 — 별도 키 운영 부담을 피한다. 서명 대상은 경로의 이미지 id 와 만료 시각뿐이라
 * JWT 와 용도가 겹치지 않는다.
 *
 * <p><b>주의</b>: 만료가 박히므로 서명 URL 을 DB 에 영구 저장하면 안 된다. 엔티티·메시지에는 상대경로 {@code /images/{id}} 를 그대로 두고,
 * HTTP 응답으로 내보내는 순간에만 서명한다.
 */
@Slf4j
@Component
public class ImageUrlSigner {

  private static final String HMAC_ALGO = "HmacSHA256";
  private static final String PATH_PREFIX = "/images/";

  private final SecretKeySpec key;
  private final long ttlSeconds;

  public ImageUrlSigner(
      @Value("${jwt.secret}") String secret,
      @Value("${image.url.ttl-seconds:3600}") long ttlSeconds) {
    byte[] keyBytes = Decoders.BASE64.decode(secret);
    this.key = new SecretKeySpec(keyBytes, HMAC_ALGO);
    this.ttlSeconds = ttlSeconds;
  }

  /**
   * 상대경로 {@code /images/{id}} 를 서명 URL 로 변환한다.
   *
   * <p>이미 절대 URL(Unsplash 등) 이거나 {@code /images/} 경로가 아니면 그대로 반환한다 — 호출 측에서 source 분기를 줄이기 위한 방어.
   *
   * @param relativeUrl {@code /images/{id}} 형태의 상대경로
   * @return 서명 쿼리가 붙은 URL, 또는 서명 대상이 아니면 입력 그대로
   */
  public String sign(String relativeUrl) {
    Long id = extractId(relativeUrl);
    if (id == null) {
      return relativeUrl;
    }
    long exp = Instant.now().getEpochSecond() + ttlSeconds;
    String sig = sign(id, exp);
    return PATH_PREFIX + id + "?exp=" + exp + "&sig=" + sig;
  }

  /**
   * 서명 검증. 만료됐거나 서명이 일치하지 않으면 false.
   *
   * @param id 경로의 이미지 id
   * @param exp 만료 epoch 초
   * @param sig 제시된 서명
   */
  public boolean verify(Long id, long exp, String sig) {
    if (id == null || sig == null || sig.isBlank()) {
      return false;
    }
    if (Instant.now().getEpochSecond() > exp) {
      return false;
    }
    String expected = sign(id, exp);
    // 타이밍 공격 회피용 상수시간 비교.
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8));
  }

  private String sign(long id, long exp) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGO);
      mac.init(key);
      byte[] raw = mac.doFinal((id + "|" + exp).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    } catch (Exception e) {
      // 키가 정상 초기화된 이상 발생하지 않는다. 발생 시 서명 불가이므로 명시적으로 실패시킨다.
      throw new IllegalStateException("이미지 URL 서명 실패", e);
    }
  }

  /** {@code /images/{id}} 에서 id 추출. 쿼리스트링·접두 mismatch 는 null. */
  private Long extractId(String url) {
    if (url == null || !url.startsWith(PATH_PREFIX)) {
      return null;
    }
    String rest = url.substring(PATH_PREFIX.length());
    int q = rest.indexOf('?');
    if (q >= 0) {
      rest = rest.substring(0, q);
    }
    try {
      return Long.parseLong(rest);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
