package com.drawe.backend.domain.search.dto;

import java.util.List;

public record ImageResult(
    Long id,
    String sourceId,
    String url,
    String photographerUsername,
    String photographerName,
    Float score,
    String technique,
    String subject,
    String mood,
    List<String> utility,
    List<String> freeTags,
    List<String> rawTags,
    String source) {}
