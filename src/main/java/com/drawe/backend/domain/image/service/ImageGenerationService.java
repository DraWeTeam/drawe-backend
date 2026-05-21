package com.drawe.backend.domain.image.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.enums.ImageSource;
import com.drawe.backend.domain.image.event.AiImageCreatedEvent;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.domain.llm.service.PromptTranslator;
import com.drawe.backend.global.client.BriaClient;
import com.drawe.backend.global.client.dto.BriaGenerateResponse;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Bria 로 이미지를 생성하고, 결과 바이트를 ImageStorage (DB) 에 영구 저장한 뒤 Image 엔티티를 만들어 반환한다.
 *
 * <p>Bria 가 돌려주는 image_url 은 임시 URL 이므로, 그대로 두면 며칠 후 깨짐. 그래서 즉시 다운로드해 우리 DB 로 옮긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

  private static final String DEFAULT_MIME = "image/png";

  private final BriaClient briaClient;
  private final ImageStorage imageStorage;
  private final ImageRepository imageRepository;
  private final PromptTranslator promptTranslator;
  private final ApplicationEventPublisher eventPublisher;
  private final RestClient downloader = RestClient.create();

  @Transactional
  public Image generate(User user, String prompt, Project project) {
    if (prompt == null || prompt.isBlank()) {
      throw new CustomException(ErrorCode.INVALID_INPUT);
    }

    // prompt 가 사용자 한국어 원문일 수도 있어 길이만 기록. 변환된 영문은 아래 PromptTranslator 로그에서 확인.
    log.info("AI 이미지 생성 요청: user={}, prompt_length={}", user.getId(), prompt.length());
    String englishPrompt = promptTranslator.translate(user, prompt, project);
    BriaGenerateResponse bria = briaClient.generate(englishPrompt);

    byte[] bytes;
    String mime;
    try {
      ResponseEntity<byte[]> entity =
          downloader.get().uri(URI.create(bria.imageUrl())).retrieve().toEntity(byte[].class);
      bytes = entity.getBody();
      mime =
          entity.getHeaders().getContentType() != null
              ? entity.getHeaders().getContentType().toString()
              : DEFAULT_MIME;
    } catch (Exception e) {
      log.error("Bria 이미지 다운로드 실패: url={}", bria.imageUrl(), e);
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    if (bytes == null || bytes.length == 0) {
      log.error("Bria 이미지가 비어있음: url={}", bria.imageUrl());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    ImageStorage.Stored stored = imageStorage.store(user, bytes, mime);

    Image image = new Image();
    image.setSource(ImageSource.AI);
    image.setUrl(stored.url());
    image.setPrompt(englishPrompt);
    image.setCreatedBy(user);
    Image saved = imageRepository.save(image);

    // ID가 확정된 뒤 sourceId를 "ai_<id>" 컨벤션으로 채운다.
    // Pinecone vector id로도 사용되며, SearchService가 이 값으로 MySQL을 조회한다.
    saved.setSourceId("ai_" + saved.getId());
    saved = imageRepository.save(saved);

    log.info(
        "AI 이미지 저장 완료: imageId={}, blobId={}, url={}", saved.getId(), stored.id(), stored.url());

    // 트랜잭션 commit 후 비동기로 CLIP 임베딩 → Pinecone 적재.
    // 직접 호출하지 않고 이벤트로 띄우는 이유: @Async 비동기 스레드는 자기만의 TX 를 새로 시작하므로
    // 호출 측 commit 전이라면 새 행을 못 본다. AiImageIndexService 가 AFTER_COMMIT 단계에서 수신.
    eventPublisher.publishEvent(new AiImageCreatedEvent(saved.getId(), bytes, mime, project));

    return saved;
  }
}
