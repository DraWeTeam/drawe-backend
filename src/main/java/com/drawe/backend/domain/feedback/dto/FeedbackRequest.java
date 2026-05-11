package com.drawe.backend.domain.feedback.dto;

import com.drawe.backend.domain.enums.FeedbackType;
import jakarta.validation.constraints.NotNull;

public record FeedbackRequest(@NotNull FeedbackType type) {}
