package com.drawe.backend.domain.onboarding.controller;

import com.drawe.backend.domain.onboarding.dto.OnboardingImage;
import com.drawe.backend.domain.onboarding.dto.OnboardingRequest;
import com.drawe.backend.domain.onboarding.dto.OnboardingStatus;
import com.drawe.backend.domain.onboarding.service.OnboardingService;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

  private final OnboardingService onboardingService;

  /** 온보딩 완료 여부 확인. */
  @GetMapping("/status")
  public ResponseEntity<ApiResponse<OnboardingStatus>> getStatus(
      @AuthenticationPrincipal PrincipalDetails principal) {
    boolean completed = onboardingService.isCompleted(principal.getUser());
    return ResponseEntity.ok(ApiResponse.success(new OnboardingStatus(completed)));
  }

  /** 온보딩용 이미지 목록 반환. */
  @GetMapping("/images")
  public ResponseEntity<ApiResponse<List<OnboardingImage>>> getOnboardingImages() {
    return ResponseEntity.ok(ApiResponse.success(onboardingService.getOnboardingImages()));
  }

  /** 온보딩 제출 (선호 태그 저장). */
  @PostMapping
  public ResponseEntity<ApiResponse<Void>> submit(
      @AuthenticationPrincipal PrincipalDetails principal,
      @Valid @RequestBody OnboardingRequest request) {

    onboardingService.saveOnboarding(principal.getUser(), request.selectedImageIds());
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
