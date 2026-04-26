package com.drawe.backend.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

  private Provider gemini = new Provider();
  private Provider grok = new Provider();
  private long timeoutMs = 60000L;
  private int maxHistory = 20;
  private String defaultProvider = "gemini";

  @Getter
  @Setter
  public static class Provider {
    private String apiKey;
    private String model;
    private String baseUrl;
  }
}
