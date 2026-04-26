package com.drawe.backend.domain.llm.dto;

import jakarta.validation.constraints.NotBlank;

public record ChatRequest(@NotBlank String message, String sessionId, String imageUrl) {}
