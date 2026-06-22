package com.drawe.backend.domain.image.service;

import com.drawe.backend.domain.User;
import com.drawe.backend.global.config.S3Properties;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * AI 이미지를 S3 에 저장하는 {@link ImageStorage} 구현체 — {@code s3} 프로파일에서 {@link Primary} 로 활성화된다.
 *
 * <p>저장된 객체 키는 {@code Image.url} 에 {@code s3:{key}} 접두 형태로 박힌다(절대 URL·만료를 DB 에 영구 저장하지 않기 위해 —
 * [[p0-image-signed-url]] 와 동일 원칙). 브라우저 노출 직전에 {@code ImageUrlSigner} 가 이 접두를 보고 S3 presigned GET
 * URL 로 변환한다. 따라서 우리 서버는 이미지 바이트를 서빙하지 않는다.
 *
 * <p><b>load() 미지원</b>: presigned 모델에선 바이트를 서버가 나르지 않는다. {@code load()} 는 {@code
 * /images/{id}}(MySQL 업로드 이미지) 경로에서만 호출되며 — {@code ImageController}· {@code ImageInputResolver} — 그
 * 경로는 {@code DbImageStorage} 가 담당한다. S3 키로의 load 는 호출되지 않으므로 명시적으로 미지원 처리한다(S3 객체를 멀티모달 입력으로 재사용하는
 * 건 향후 범위).
 */
@Slf4j
@Service
@Primary
@Profile("s3")
public class S3ImageStorage implements ImageStorage {

  /** {@code Image.url} 에 박히는 S3 키 접두. {@code ImageUrlSigner} 가 이 접두로 presign 분기한다. */
  public static final String S3_URL_PREFIX = "s3:";

  private static final String DEFAULT_EXT = "png";

  private final S3Client s3Client;
  private final S3Properties props;

  public S3ImageStorage(S3Client s3Client, S3Properties props) {
    this.s3Client = s3Client;
    this.props = props;
  }

  @Override
  public Stored store(User owner, byte[] data, String mimeType) {
    if (data == null || data.length == 0) {
      throw new CustomException(ErrorCode.INVALID_INPUT);
    }
    String key = props.getKeyPrefix() + "/" + UUID.randomUUID() + "." + extOf(mimeType);
    try {
      PutObjectRequest req =
          PutObjectRequest.builder()
              .bucket(props.getBucket())
              .key(key)
              .contentType(mimeType)
              .contentLength((long) data.length)
              .build();
      s3Client.putObject(req, RequestBody.fromBytes(data));
    } catch (S3Exception e) {
      // AWS 에러 코드(AccessDenied/InvalidAccessKeyId/SignatureDoesNotMatch/NoSuchBucket 등)까지 남긴다 —
      // status=403 만으론 권한 부족인지 자격증명 문제인지 버킷 부재인지 구분이 안 된다.
      log.error(
          "S3 업로드 실패: bucket={}, key={}, status={}, awsErrorCode={}, msg={}",
          props.getBucket(),
          key,
          e.statusCode(),
          e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "unknown",
          e.awsErrorDetails() != null ? e.awsErrorDetails().errorMessage() : e.getMessage());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
    log.info("S3 이미지 저장 완료: bucket={}, key={}, size={}", props.getBucket(), key, data.length);
    // id 는 S3 에 없으므로 null. url 은 s3:{key} — 노출 직전 ImageUrlSigner 가 presign.
    return new Stored(null, S3_URL_PREFIX + key);
  }

  @Override
  public Loaded load(Long id) {
    // presigned 모델에선 서버가 바이트를 나르지 않는다(클래스 javadoc 참조). 호출되지 않아야 정상.
    throw new UnsupportedOperationException(
        "S3ImageStorage 는 바이트 서빙(load)을 지원하지 않는다 — presigned URL 로 직접 접근한다");
  }

  /** mime → 파일 확장자. 알 수 없으면 png. */
  private static String extOf(String mimeType) {
    if (mimeType == null) {
      return DEFAULT_EXT;
    }
    int slash = mimeType.indexOf('/');
    if (slash < 0 || slash == mimeType.length() - 1) {
      return DEFAULT_EXT;
    }
    String sub = mimeType.substring(slash + 1).toLowerCase();
    // image/jpeg → jpg 관용 처리, 그 외 서브타입(png/webp/gif)은 그대로.
    return switch (sub) {
      case "jpeg" -> "jpg";
      case "png", "webp", "gif", "jpg" -> sub;
      default -> DEFAULT_EXT;
    };
  }
}
