package com.drawe.backend.controller;

import com.drawe.backend.domain.User;
import com.drawe.backend.dto.TokenRefreshRequest;
import com.drawe.backend.repository.UserRepository;
import com.drawe.backend.security.JwtProvider;
import com.drawe.backend.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/auth")
public class LogoutController {

    private final RefreshTokenService refreshTokenService;
    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;

    @PostMapping("/logout")
    public void logout(@RequestBody TokenRefreshRequest request) {
        refreshTokenService.deleteByToken(request.getRefreshToken());
    }

    @PostMapping("/logout/all")
    public void logoutAll(@RequestHeader("Authorization") String authorizationHeader) {
        String accessToken = authorizationHeader.substring(7);
        Long userId = jwtProvider.getUserId(accessToken);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        refreshTokenService.deleteAllByUser(user);
    }
}