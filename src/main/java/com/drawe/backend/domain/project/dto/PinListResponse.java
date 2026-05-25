package com.drawe.backend.domain.project.dto;

import java.util.List;

public record PinListResponse(List<PinItem> pins, int count, int maxSlots) {}
