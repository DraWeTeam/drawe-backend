package com.drawe.backend.domain.llm.dto;

public record LlmCallResult(String content, String model, int latencyMs) {}
