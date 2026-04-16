package com.drawe.backend.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PasswordCheckRequest {

    @NotBlank(message = "비밀번호는 필수입니다")
    private String password;
}
