package com.drawe.backend.domain.image.controller;

import com.drawe.backend.domain.image.dto.ImageUploadResponse;
import com.drawe.backend.domain.image.service.ImageStorage;
import com.drawe.backend.domain.image.service.ImageUploadService;
import com.drawe.backend.domain.image.service.ImageUrlSigner;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/images")
@RequiredArgsConstructor
public class ImageController {

  private final ImageUploadService imageUploadService;
  private final ImageStorage imageStorage;
  private final ImageUrlSigner imageUrlSigner;

  @PostMapping("/upload")
  public ApiResponse<ImageUploadResponse> upload(
      @AuthenticationPrincipal PrincipalDetails principal,
      @RequestParam("file") MultipartFile file) {
    ImageStorage.Stored stored = imageUploadService.upload(principal.getUser(), file);
    return ApiResponse.success(new ImageUploadResponse(stored.id(), stored.url()));
  }

  /**
   * 이미지 바이트 서빙. 브라우저 {@code <img src>} 가 직접 호출하므로 토큰·소유자 검증 대신 서명(exp+sig)으로 접근 제어한다 ({@link
   * ImageUrlSigner}). 서명 URL 은 {@code SearchService}/{@code ChatLlmService} 가 노출 직전에 발급한다.
   */
  @GetMapping("/{id}")
  public ResponseEntity<byte[]> view(
      @PathVariable Long id,
      @RequestParam(name = "exp", required = false) Long exp,
      @RequestParam(name = "sig", required = false) String sig) {
    if (exp == null || !imageUrlSigner.verify(id, exp, sig)) {
      throw new CustomException(ErrorCode.FORBIDDEN);
    }
    ImageStorage.Loaded loaded = imageStorage.load(id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(loaded.mimeType()))
        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
        .body(loaded.data());
  }
}
