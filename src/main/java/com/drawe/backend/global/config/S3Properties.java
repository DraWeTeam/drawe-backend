package com.drawe.backend.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI 이미지 S3 저장 설정({@code app.s3.*}).
 *
 * <p>기본(로컬/테스트)은 {@code bucket} 이 비어 있고 {@code s3} 프로파일이 꺼져 있어 사용되지 않는다 — 그땐 {@code
 * DbImageStorage}(MySQL) 가 동작한다. 운영에서 {@code s3} 프로파일 + 값 주입 시 {@code S3ImageStorage} 가 활성화된다(설계:
 * AI 이미지 저장 S3 전환).
 */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app.s3")
public class S3Properties {

  /** 버킷 이름. */
  private String bucket;

  /** 리전(예: ap-northeast-2). */
  private String region;

  /** 객체 키 접두 — AI 생성 이미지를 한 prefix 아래로 모은다. */
  private String keyPrefix = "ai";

  /** presigned GET URL 유효 시간(초). */
  private long presignTtlSeconds = 3600;
}
