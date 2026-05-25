package com.drawe.backend.global.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BriaGenerateResponse(@JsonProperty("image_url") String imageUrl) {}
