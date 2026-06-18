package com.drawe.backend.domain.gallery.dto;

import java.util.List;

/** 완성작 갤러리 목록 응답. ProjectListResponse 와 동일한 페이징 계약(items/total/hasMore). */
public record GalleryResponse(List<GalleryItem> items, long total, boolean hasMore) {}
