package com.drawe.backend.controller;

import com.drawe.backend.security.PrincipalDetails;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserController {

    @GetMapping("/user/profile")
    public Map<String, Object> me(@AuthenticationPrincipal PrincipalDetails user) {
        return Map.of(
                "id", user.getUser().getId(),
                "email", user.getUser().getEmail(),
                "nickname", user.getUser().getNickname(),
                "picture", user.getUser().getPicture()
        );
    }
}
