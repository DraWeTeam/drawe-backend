package com.drawe.backend.domain.project.dto;

import jakarta.validation.constraints.NotNull;

public record PinAddRequest(@NotNull Long imageId) {
}
