package com.drawe.backend.domain.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawe.backend.global.config.S3Properties;
import com.drawe.backend.global.error.CustomException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** S3ImageStorage 단위 테스트 — S3Client 는 mock. store() 의 키 생성·PutObject 인자, load() 미지원을 검증. */
class S3ImageStorageTest {

  private S3Client s3Client;
  private S3ImageStorage storage;

  @BeforeEach
  void setUp() {
    s3Client = mock(S3Client.class);
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenReturn(PutObjectResponse.builder().build());
    S3Properties props = new S3Properties();
    props.setBucket("test-bucket");
    props.setRegion("ap-northeast-2");
    props.setKeyPrefix("ai");
    storage = new S3ImageStorage(s3Client, props);
  }

  @Test
  @DisplayName("store: 버킷·키접두·contentType 으로 PutObject, url 은 s3:{prefix}/{uuid}.{ext}")
  void store_putsObjectWithExpectedRequest() {
    ImageStorage.Stored stored = storage.store(null, new byte[] {1, 2, 3}, "image/png");

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
    PutObjectRequest req = captor.getValue();

    assertThat(req.bucket()).isEqualTo("test-bucket");
    assertThat(req.key()).startsWith("ai/").endsWith(".png");
    assertThat(req.contentType()).isEqualTo("image/png");
    assertThat(req.contentLength()).isEqualTo(3L);

    // url 은 s3: 접두 + 동일 키. id 는 S3 에 없으므로 null.
    assertThat(stored.id()).isNull();
    assertThat(stored.url()).isEqualTo(S3ImageStorage.S3_URL_PREFIX + req.key());
  }

  @Test
  @DisplayName("store: image/jpeg → .jpg, 알 수 없는 mime → .png")
  void store_extensionMapping() {
    ImageStorage.Stored jpg = storage.store(null, new byte[] {1}, "image/jpeg");
    assertThat(jpg.url()).endsWith(".jpg");

    ImageStorage.Stored unknown = storage.store(null, new byte[] {1}, "application/octet-stream");
    assertThat(unknown.url()).endsWith(".png");
  }

  @Test
  @DisplayName("store: 빈 데이터는 INVALID_INPUT")
  void store_emptyData_throws() {
    assertThatThrownBy(() -> storage.store(null, new byte[0], "image/png"))
        .isInstanceOf(CustomException.class);
  }

  @Test
  @DisplayName("load: presigned 모델이라 미지원 — UnsupportedOperationException")
  void load_unsupported() {
    assertThatThrownBy(() -> storage.load(1L)).isInstanceOf(UnsupportedOperationException.class);
  }
}
