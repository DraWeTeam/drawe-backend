package com.drawe.backend.domain.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CheckAvailabilityResponse {
  private boolean available;
}
