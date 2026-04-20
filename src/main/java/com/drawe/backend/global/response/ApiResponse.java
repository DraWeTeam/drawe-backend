package com.drawe.backend.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

  private final boolean success;
  private final T data;
  private final String message;

  private ApiResponse(boolean success, T data, String message) {
    this.success = success;
    this.data = data;
    this.message = message;
  }

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static <T> ApiResponse<T> success(T data, String message) {
    return new ApiResponse<>(true, data, message);
  }

  public static ApiResponse<Void> success() {
    return new ApiResponse<>(true, null, null);
  }
}
