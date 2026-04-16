package com.drawe.backend.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class SignupResponse {

    private Long userId;
    private String email;
    private String nickname;
}
