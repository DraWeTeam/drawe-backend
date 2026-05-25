package com.drawe.backend.domain.project.controller;

import com.drawe.backend.domain.project.dto.PinAddRequest;
import com.drawe.backend.domain.project.dto.PinListResponse;
import com.drawe.backend.domain.project.service.PinService;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/projects/{projectId}/pins")
@RequiredArgsConstructor
@Tag(name = "Pin", description = "프로젝트 핀(책갈피) API")
public class PinController {

  private final PinService pinService;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "핀 추가", description = "프로젝트에 이미지를 핀합니다. 최대 3개까지 가능.")
  public ApiResponse<PinListResponse> add(
      @AuthenticationPrincipal PrincipalDetails principalDetails,
      @PathVariable Long projectId,
      @Valid @RequestBody PinAddRequest pinAddRequest) {
    pinService.addPins(principalDetails.getUser(), projectId, pinAddRequest.imageId());
    return ApiResponse.success(pinService.getPins(principalDetails.getUser(), projectId));
  }

  @DeleteMapping("/{imageId}")
  @Operation(summary = "핀 해제", description = "핀된 이미지를 해제합니다.")
  public ApiResponse<Map<String, Boolean>> delete(
      @AuthenticationPrincipal PrincipalDetails principalDetails,
      @PathVariable Long projectId,
      @PathVariable Long imageId) {
    pinService.removePin(principalDetails.getUser(), projectId, imageId);
    return ApiResponse.success(Map.of("success", true));
  }

  @GetMapping
  @Operation(summary = "핀 목록 조회", description = "프로젝트에 핀된 이미지 목록을 반환합니다.")
  public ApiResponse<PinListResponse> list(
      @AuthenticationPrincipal PrincipalDetails principalDetails, @PathVariable Long projectId) {
    return ApiResponse.success(pinService.getPins(principalDetails.getUser(), projectId));
  }
}
