package com.drawe.backend.domain.feedback.controller;

import com.drawe.backend.domain.feedback.dto.FeedbackRequest;
import com.drawe.backend.domain.feedback.service.ImageFeedbackService;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/images")
@RequiredArgsConstructor
public class ImageFeedbackController {

  private final ImageFeedbackService feedbackService;

  @PostMapping("/{imageId}/feedback")
  public ResponseEntity<ApiResponse<Void>> saveFeedback(
      @AuthenticationPrincipal PrincipalDetails principal,
      @PathVariable Long imageId,
      @Valid @RequestBody FeedbackRequest request) {

    feedbackService.saveFeedback(principal.getUser(), imageId, request.type());
    return ResponseEntity.ok(ApiResponse.success(null));
  }

  @DeleteMapping("/{imageId}/feedback")
  public ResponseEntity<ApiResponse<Void>> removeFeedback(
      @AuthenticationPrincipal PrincipalDetails principal, @PathVariable Long imageId) {

    feedbackService.removeFeedback(principal.getUser(), imageId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
