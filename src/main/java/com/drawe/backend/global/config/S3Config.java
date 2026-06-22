package com.drawe.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * S3 클라이언트 빈 — {@code s3} 프로파일에서만 생성된다.
 *
 * <p>프로파일이 꺼져 있으면(로컬/테스트 기본) 이 빈들이 아예 만들어지지 않으므로 자격증명·버킷이 없어도 안전하다. 그땐 {@code
 * DbImageStorage}(MySQL) 가 동작한다.
 *
 * <p>자격증명은 키를 코드/설정에 박지 않고 {@link DefaultCredentialsProvider} 가 표준 체인 (ECS/EC2 IAM Role → 환경변수 →
 * {@code ~/.aws/credentials}) 으로 해석한다.
 *
 * <p>HTTP 클라이언트는 {@link UrlConnectionHttpClient} 를 명시한다 — 동기 S3Client 는 HTTP 구현체가 필요한데 {@code
 * awssdk:s3} 가 자동으로 안 끌고 오기 때문(없으면 런타임에 Apache HC5 를 찾다 NoClassDefFoundError). PutObject +
 * presigned 용도엔 JDK HttpURLConnection 기반으로 충분하고 의존성이 가볍다.
 */
@Configuration
@Profile("s3")
public class S3Config {

  private final S3Properties props;

  public S3Config(S3Properties props) {
    this.props = props;
  }

  @Bean
  public S3Client s3Client() {
    return S3Client.builder()
        .region(Region.of(props.getRegion()))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .httpClient(UrlConnectionHttpClient.create())
        .build();
  }

  @Bean
  public S3Presigner s3Presigner() {
    return S3Presigner.builder()
        .region(Region.of(props.getRegion()))
        .credentialsProvider(DefaultCredentialsProvider.create())
        .build();
  }
}
