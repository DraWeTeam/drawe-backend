package com.drawe.backend.domain.auth.controller;

import com.drawe.backend.domain.auth.dto.MyProfileResponse;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController {

  @GetMapping("/user/profile")
  public ApiResponse<MyProfileResponse> me(@AuthenticationPrincipal PrincipalDetails user) {
    return ApiResponse.success(
        new MyProfileResponse(
            user.getUser().getId(),
            user.getUser().getEmail(),
            user.getUser().getNickname(),
            user.getUser().getPicture()));
  }
}
