package com.drawe.backend.domain.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GenerateImageRequest(@NotBlank @Size(max = 2000) String prompt) {}
