package com.drawe.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drawe.backend.global.config.S3Properties;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/** ImageUrlSigner 단위 테스트 — HMAC(MySQL) 분기 유지 + S3 presign 분기 + presigner 없을 때 방어. */
class ImageUrlSignerTest {

  // 유효한 BASE64 시크릿(HmacSHA256 키로 디코딩 가능하면 됨).
  private static final String SECRET =
      "dGVzdC1zZWNyZXQtZm9yLWltYWdlLXVybC1zaWduZXItMTIzNDU2Nzg5MA==";

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> p = mock(ObjectProvider.class);
    when(p.getIfAvailable()).thenReturn(value);
    return p;
  }

  @Nested
  @DisplayName("HMAC 분기 (MySQL /images/{id}) — 기존 동작 유지")
  class HmacBranch {

    private final ImageUrlSigner signer =
        new ImageUrlSigner(SECRET, 3600, providerOf(null), providerOf(null));

    @Test
    @DisplayName("/images/{id} 는 exp·sig 가 붙고, verify 가 통과")
    void signsAndVerifies() {
      String signed = signer.sign("/images/42");
      assertThat(signed).startsWith("/images/42?exp=").contains("&sig=");

      // 붙은 exp/sig 를 파싱해 verify 통과 확인.
      long exp = Long.parseLong(signed.replaceAll(".*exp=(\\d+).*", "$1"));
      String sig = signed.replaceAll(".*&sig=", "");
      assertThat(signer.verify(42L, exp, sig)).isTrue();
    }

    @Test
    @DisplayName("절대 URL(Unsplash 등)은 그대로 통과")
    void absoluteUrlUntouched() {
      String url = "https://images.unsplash.com/photo-123";
      assertThat(signer.sign(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("위조 서명은 verify 실패")
    void forgedSignatureRejected() {
      long exp = System.currentTimeMillis() / 1000 + 3600;
      assertThat(signer.verify(42L, exp, "forged")).isFalse();
    }
  }

  @Nested
  @DisplayName("S3 presign 분기")
  class S3Branch {

    @Test
    @DisplayName("s3:{key} 는 presigner 가 발급한 GET URL 로 변환")
    void presignsS3Key() throws Exception {
      S3Presigner presigner = mock(S3Presigner.class);
      PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
      URI url =
          URI.create(
              "https://test-bucket.s3.ap-northeast-2.amazonaws.com/ai/x.png?X-Amz-Signature=abc");
      when(presigned.url()).thenReturn(url.toURL());
      when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);

      S3Properties props = new S3Properties();
      props.setBucket("test-bucket");
      props.setRegion("ap-northeast-2");
      props.setPresignTtlSeconds(900);

      ImageUrlSigner signer =
          new ImageUrlSigner(SECRET, 3600, providerOf(presigner), providerOf(props));

      String result = signer.sign("s3:ai/x.png");
      assertThat(result).isEqualTo(url.toString());
    }

    @Test
    @DisplayName("presigner 가 없으면(프로파일 꺼짐) s3: 입력은 원본 그대로 반환(방어)")
    void noPresigner_returnsRawDefensively() {
      ImageUrlSigner signer = new ImageUrlSigner(SECRET, 3600, providerOf(null), providerOf(null));
      assertThat(signer.sign("s3:ai/x.png")).isEqualTo("s3:ai/x.png");
    }
  }
}
