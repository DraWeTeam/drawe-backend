package com.drawe.backend.domain.llm.dto;

public record GenerateImageResponse(
    String sessionId, Long imageId, String imageUrl, String prompt) {}
