package com.drawe.backend.domain.image.service;

import com.drawe.backend.domain.User;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.io.IOException;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageUploadService {

  private static final Set<String> ALLOWED_MIMES =
      Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

  private final ImageStorage imageStorage;

  @Value("${app.image.max-size-bytes:10485760}")
  private long maxSizeBytes;

  public ImageStorage.Stored upload(User user, MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT);
    }
    if (file.getSize() > maxSizeBytes) {
      throw new CustomException(ErrorCode.INVALID_INPUT);
    }
    String mime = file.getContentType();
    if (mime == null || !ALLOWED_MIMES.contains(mime)) {
      throw new CustomException(ErrorCode.INVALID_INPUT);
    }
    try {
      return imageStorage.store(user, file.getBytes(), mime);
    } catch (IOException e) {
      log.error("Failed to read upload bytes: error_class={}", e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR);
    }
  }
}
