package com.drawe.backend.domain.llm.service;

import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * imageUrl 필드 처리.
 *
 * <p>Phase 1: data:image/...;base64,... 형식만 지원.
 *
 * <p>Phase 2(Cloudinary): http(s):// URL 다운로드 분기 추가 예정.
 */
@Component
public class ImageInputResolver {

  private static final Pattern DATA_URL =
      Pattern.compile("^data:(?<mime>[^;]+);base64,(?<data>.+)$", Pattern.DOTALL);

  public Resolved resolve(String imageUrl) {
    if (imageUrl == null || imageUrl.isBlank()) {
      return Resolved.empty();
    }
    Matcher m = DATA_URL.matcher(imageUrl);
    if (m.matches()) {
      try {
        byte[] bytes = Base64.getDecoder().decode(m.group("data"));
        return new Resolved(bytes, m.group("mime"));
      } catch (IllegalArgumentException e) {
        throw new CustomException(ErrorCode.INVALID_INPUT);
      }
    }
    // Phase 2: http URL 다운로드. 지금은 미지원.
    throw new CustomException(ErrorCode.INVALID_INPUT);
  }

  public record Resolved(byte[] bytes, String mimeType) {
    public static Resolved empty() {
      return new Resolved(new byte[0], null);
    }

    public boolean hasImage() {
      return bytes != null && bytes.length > 0;
    }
  }
}
