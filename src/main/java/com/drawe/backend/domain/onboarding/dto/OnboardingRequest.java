package com.drawe.backend.domain.onboarding.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record OnboardingRequest(
    @NotEmpty(message = "최소 1개 이상 선택해주세요") List<Long> selectedImageIds) {}
