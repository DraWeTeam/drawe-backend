package com.drawe.backend.domain.gallery.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 레퍼런스 아카이브 — 프로젝트별 섹션 구조. 각 섹션은 한 프로젝트와 그 프로젝트에 담긴 레퍼런스 이미지 목록이다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReferenceArchiveResponse(List<ProjectSection> sections) {

  public record ProjectSection(
      Long projectId, String projectName, List<ReferenceImageItem> references) {}

  public record ReferenceImageItem(Long imageId, String url) {}
}
