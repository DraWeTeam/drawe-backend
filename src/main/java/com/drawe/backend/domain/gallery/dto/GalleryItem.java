package com.drawe.backend.domain.gallery.dto;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.image.service.ImageUrlSigner;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/** 완성작 갤러리 항목. url 은 노출 직전 {@link ImageUrlSigner} 로 서명한다(브라우저 직접 로드). */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record GalleryItem(Long id, String url, String prompt, Instant createdAt) {

  public static GalleryItem of(Image image, ImageUrlSigner signer) {
    return new GalleryItem(
        image.getId(),
        signer.sign(image.getUrl()),
        image.getPrompt(),
        image.getCreatedAt());
  }
}
