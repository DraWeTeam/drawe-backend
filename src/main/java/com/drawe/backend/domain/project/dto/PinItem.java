package com.drawe.backend.domain.project.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PinItem(
    Long id,
    String url,
    String photographerName,
    String photographerUsername,
    String source,
    String technique,
    String subject,
    String mood,
    List<String> rawTags) {}
