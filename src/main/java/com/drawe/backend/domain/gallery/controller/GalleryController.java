package com.drawe.backend.domain.gallery.controller;

import com.drawe.backend.domain.gallery.dto.GalleryResponse;
import com.drawe.backend.domain.gallery.dto.ReferenceArchiveResponse;
import com.drawe.backend.domain.gallery.service.GalleryService;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gallery")
@RequiredArgsConstructor
@Validated
public class GalleryController {

  private final GalleryService galleryService;

  /** 내 완성작 갤러리 — 로그인 유저가 생성한 AI 이미지 최신순 페이징. */
  @GetMapping("/completed")
  public ApiResponse<GalleryResponse> completed(
      @AuthenticationPrincipal PrincipalDetails principal,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return ApiResponse.success(galleryService.getCompleted(principal.getUser(), page, size));
  }

  /** 레퍼런스 아카이브 — 내 프로젝트별 레퍼런스 이미지 섹션. */
  @GetMapping("/references")
  public ApiResponse<ReferenceArchiveResponse> references(
      @AuthenticationPrincipal PrincipalDetails principal) {
    return ApiResponse.success(galleryService.getReferenceArchive(principal.getUser()));
  }
}
