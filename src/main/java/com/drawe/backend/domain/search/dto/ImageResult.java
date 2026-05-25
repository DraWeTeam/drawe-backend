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
    /** ImageSource enum name: "UNSPLASH" | "AI". 프론트는 "AI"일 때 생성 이미지 배지를 렌더한다. */
    String source) {}
