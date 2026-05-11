package com.drawe.backend.domain.feedback.dto;

import com.drawe.backend.domain.enums.FeedbackType;

public record FeedbackResponse(FeedbackType type // null이면 피드백 없음
    ) {}
