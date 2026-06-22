package com.drawe.backend.domain.image.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.ImageDraweTag;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.image.event.AiImageCreatedEvent;
import com.drawe.backend.domain.image.repository.ImageDraweTagRepository;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.global.client.FastApiClient;
import com.drawe.backend.global.client.PineconeClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AI 생성 이미지를 CLIP 임베딩 → Pinecone upsert 까지 책임지는 비동기 인덱서.
 *
 * <p>설계 결정:
 *
 * <ul>
 *   <li>비동기로 분리해 사용자 응답 지연을 막는다.
 *   <li>실패해도 사용자 응답은 이미 끝났으므로 로그만 남기고 throw 하지 않는다.
 *   <li>성공 시 {@link Image#getIndexedAt()}을 채워, 미인덱싱 이미지를 추후 수동/배치 재처리할 수 있게 한다.
 * </ul>
 *
 * <p>주의: @Async 진입 메서드와 @Transactional 메서드는 분리되어 있어야 프록시 체인이 정상 동작한다. 트랜잭션 작업은 {@link
 * AiImageIndexTxService}로 위임한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiImageIndexService {

  private final FastApiClient fastApiClient;
  private final PineconeClient pineconeClient;
  private final ImageRepository imageRepository;
  private final AiImageIndexTxService txService;

  /**
   * AFTER_COMMIT 단계에서 이벤트를 받아 비동기 인덱싱을 트리거.
   *
   * <p>호출 측 트랜잭션이 commit 된 뒤에 실행되므로, 비동기 스레드에서 findById 가 새 행을 정상적으로 본다. (이전에는 commit 전에 @Async 가
   * 돌아 "AI 이미지 적재 대상 없음" 로그가 찍히던 문제.)
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onAiImageCreated(AiImageCreatedEvent event) {
    indexAsync(event.imageId(), event.imageBytes(), event.mimeType(), event.project());
  }

  /**
   * Bria 로부터 받은 이미지 바이트를 CLIP 임베딩하고 Pinecone 에 적재한다.
   *
   * <p>Image 는 호출 측 트랜잭션에서 이미 저장된 상태여야 한다 (id, sourceId 확정). 메타데이터 중 subject/technique/mood 는 프로젝트
   * 정보를 상속한다.
   */
  @Async
  public void indexAsync(Long imageId, byte[] imageBytes, String mimeType, Project project) {
    try {
      Image image = imageRepository.findById(imageId).orElse(null);
      if (image == null) {
        log.warn("AI 이미지 적재 대상 없음: imageId={}", imageId);
        return;
      }
      if (image.getSourceId() == null || image.getSourceId().isBlank()) {
        log.warn("sourceId 미설정 — Pinecone vector id 결정 불가: imageId={}", imageId);
        return;
      }
      if (image.getIndexedAt() != null) {
        log.info("이미 적재됨 — skip: imageId={}", imageId);
        return;
      }

      List<Float> vector = fastApiClient.embedImage(imageBytes, mimeType);
      Map<String, Object> metadata = buildMetadata(image, project);
      pineconeClient.upsert(image.getSourceId(), vector, metadata);

      txService.markIndexedAndSeedTag(imageId, project);
      log.info("AI 이미지 Pinecone 적재 완료: imageId={}, sourceId={}", imageId, image.getSourceId());
    } catch (Exception e) {
      // 비동기 경계 — throw 해도 받을 사람이 없으므로 로그만 남긴다.
      // indexed_at 미설정으로 남으므로 추후 재처리 대상이 된다.
      log.error(
          "AI 이미지 Pinecone 적재 실패: imageId={}, error_class={}",
          imageId,
          e.getClass().getSimpleName());
    }
  }

  /**
   * 일반(Bria) AI 이미지의 Pinecone 메타 출처값. 코랩 Unsplash 시드와 구분한다.
   *
   * <p>가이드용(FastAPI+Gemini) 이미지는 별도 namespace 로 분리 적재하되, 같은 메타 키({@code image_source})에 다른 출처값을 넣어
   * namespace 안에서도 출처를 식별할 수 있게 한다. 가이드 출처값· 적재 경로는 가이드 FastAPI 스키마 확정 시 추가. 설계: {@code
   * docs/decisions/S3-guide-namespace-design.md}.
   */
  static final String SOURCE_AI_GENERATED = "AI_GENERATED";

  private Map<String, Object> buildMetadata(Image image, Project project) {
    return buildMetadata(image, project, SOURCE_AI_GENERATED);
  }

  /**
   * Pinecone 메타 구성. {@code imageSource} 로 출처(일반 vs 가이드)를 주입받는다.
   *
   * <p>코랩 시드 노트북 컨벤션과 동일한 키 사용:
   *
   * <ul>
   *   <li>image_source: 출처 구분 (일반={@link #SOURCE_AI_GENERATED}, Unsplash 시드와 구분)
   *   <li>ai_subject/ai_technique/ai_mood: List&lt;String&gt; (시드 동일 키와 충돌 방지용 ai_ 프리픽스)
   *   <li>generation_prompt: AI 이미지는 원본 keywords가 없으므로 프롬프트 저장
   * </ul>
   *
   * <p>null/빈 값은 키 자체를 제외한다.
   */
  private Map<String, Object> buildMetadata(Image image, Project project, String imageSource) {
    Map<String, Object> m = new HashMap<>();
    m.put("image_source", imageSource);
    if (image.getCreatedBy() != null && image.getCreatedBy().getId() != null) {
      m.put("createdByUserId", image.getCreatedBy().getId());
    }
    if (image.getPrompt() != null && !image.getPrompt().isBlank()) {
      m.put("generation_prompt", image.getPrompt());
    }
    if (project != null) {
      if (project.getSubject() != null) {
        m.put("ai_subject", List.of(project.getSubject()));
      }
      if (project.getTechnique() != null) {
        m.put("ai_technique", List.of(project.getTechnique()));
      }
      if (project.getMood() != null) {
        m.put("ai_mood", List.of(project.getMood()));
      }
    }
    return m;
  }

  /** {@link AiImageIndexService} 의 트랜잭션 영역 — 자기 호출 프록시 문제를 피하기 위해 분리. */
  @Slf4j
  @Service
  @RequiredArgsConstructor
  static class AiImageIndexTxService {

    private final ImageRepository imageRepository;
    private final ImageDraweTagRepository imageDraweTagRepository;

    // REQUIRES_NEW: AFTER_COMMIT phase는 호출 측 TX 가 끝난 뒤지만,
    // Spring 의 transaction synchronization 흐름 안에서 호출되므로 propagation 기본값(REQUIRED)이
    // 새 TX를 안 여는 케이스가 있다. 명시적으로 새 TX 를 열어 indexed_at 업데이트가 확실히 commit 되게 한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markIndexedAndSeedTag(Long imageId, Project project) {
      log.info("markIndexedAndSeedTag 진입: imageId={}", imageId);
      Image image = imageRepository.findById(imageId).orElse(null);
      if (image == null) {
        log.warn("markIndexedAndSeedTag: image null, imageId={}", imageId);
        return;
      }
      image.setIndexedAt(Instant.now());
      image.setIsTagged(Boolean.TRUE);
      imageRepository.save(image);
      log.info("markIndexedAndSeedTag: indexed_at 설정 + save, imageId={}", imageId);

      if (project == null) {
        return;
      }
      boolean alreadyTagged = !imageDraweTagRepository.findByImageIdIn(List.of(imageId)).isEmpty();
      if (alreadyTagged) {
        return;
      }
      ImageDraweTag tag = new ImageDraweTag();
      tag.setImage(image);
      tag.setSubject(truncate(project.getSubject(), 30));
      tag.setTechnique(truncate(project.getTechnique(), 30));
      tag.setMood(truncate(project.getMood(), 30));
      tag.setTaggedBy("AI");
      tag.setTaggedAt(Instant.now());
      imageDraweTagRepository.save(tag);
    }

    private String truncate(String s, int max) {
      if (s == null) {
        return null;
      }
      return s.length() > max ? s.substring(0, max) : s;
    }
  }
}
