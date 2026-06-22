package com.drawe.backend.domain.search.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.ImageDraweTag;
import com.drawe.backend.domain.image.repository.ImageDraweTagRepository;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchRequest;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.global.client.FastApiClient;
import com.drawe.backend.global.client.PineconeClient;
import com.drawe.backend.global.client.dto.PineconeMatch;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

  private final FastApiClient fastApiClient;
  private final PineconeClient pineconeClient;
  private final ImageRepository imageRepository;
  private final ImageDraweTagRepository imageDraweTagRepository;

  /**
   * 텍스트 쿼리(검색어)를 받아 유사도 검색 결과를 반환
   *
   * <p>처리 흐름: 1. FastAPI로 쿼리를 768차원 벡터로 변환 2. Pinecone에서 top-K 유사 이미지 ID와 점수 조회 3. MySQL에서 해당 이미지들의
   * 메타데이터를 한 번에 조회 4. Pinecone 순위를 유지하며 응답 조립
   *
   * <p><b>트랜잭션 분리(REQUIRES_NEW):</b> 외부 호출(embed/Pinecone) 장애 시 예외가 이 프록시 경계를 빠져나가며 트랜잭션을
   * rollback-only 로 마킹한다. 만약 호출자({@code ChatLlmService.chat()})의 트랜잭션에 참여(REQUIRED)하면, 호출자가 예외를
   * graceful 하게 catch 해도 호출자 트랜잭션이 이미 더럽혀져 커밋 시점에 {@code UnexpectedRollbackException}(500)이 난다.
   * REQUIRES_NEW 로 별도 트랜잭션을 띄워 검색 실패가 호출자 트랜잭션을 오염시키지 않게 한다. 읽기 쿼리 2개는 한 스냅샷으로 유지.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public SearchResponse search(SearchRequest request) {
    String query = request.query();
    int topK = request.getTopK();

    log.info("topK:{}", topK);

    // 1. 텍스트 -> 벡터
    List<Float> vector = fastApiClient.embedText(query);

    // 2~6. 벡터 -> Pinecone -> MySQL -> 결과 조립 (벡터 출처와 무관한 공통 경로)
    return searchByVectorInternal(vector, topK, "query_length=" + query.length(), query);
  }

  /**
   * 이미지(또는 임의) 벡터로 유사도 검색. 텍스트 {@link #search} 와 동일한 CLIP 공간·동일 조립 경로를 공유하되 1단계(텍스트 임베딩)만 건너뛴다. 010
   * SELF_CRITIQUE 의 "내 작업물과 비슷한 레퍼런스 첨부"(a2)에서 {@code FastApiClient.embedImage} 결과를 그대로 넘겨 호출한다.
   * 설계: {@code docs/decisions/S3A-self-critique-design.md} §7.
   *
   * <p>트랜잭션·외부호출 격리 정책은 {@link #search} 와 동일(REQUIRES_NEW) — 검색 실패가 호출자 트랜잭션을 오염시키지 않는다.
   *
   * @param vector 검색 기준 768차원 벡터 (CLIP). 호출 측이 embedImage 등으로 준비.
   * @param topK 반환 상한
   * @return Pinecone 순위 유지 결과. {@code SearchResponse.query} 는 벡터 검색이라 빈 문자열.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public SearchResponse searchByVector(List<Float> vector, int topK) {
    log.info("searchByVector topK:{}", topK);
    return searchByVectorInternal(vector, topK, "vector_search", "");
  }

  /**
   * 벡터 → Pinecone → MySQL 메타 → ImageResult 조립 공통 경로(텍스트·이미지 검색 공유). 트랜잭션 경계는 진입 public
   * 메서드(REQUIRES_NEW)에 있고, 이 private 헬퍼는 그 안에서 호출된다.
   *
   * @param logCtx 로그용 컨텍스트 문자열(쿼리 길이 등 — 원문 노출 회피)
   * @param echoQuery 응답 {@link SearchResponse#query} 에 담을 값(텍스트 검색은 query, 벡터 검색은 빈 문자열)
   */
  private SearchResponse searchByVectorInternal(
      List<Float> vector, int topK, String logCtx, String echoQuery) {
    // 2. 벡터 -> Pinecone 검색
    List<PineconeMatch> matches = pineconeClient.queryByVector(vector, topK);
    if (matches.isEmpty()) {
      log.info("검색 결과 없음: {}", logCtx);
      return new SearchResponse(List.of(), 0, echoQuery);
    }

    // 3. ID 추출 -> MySQL에서 메타데이터 한 번에 조회
    List<String> sourceIds = matches.stream().map(PineconeMatch::id).toList();

    List<Image> images = imageRepository.findBySourceIdIn(sourceIds);

    // 4. 이미지 ID 추출 -> 태그 한 번에 조회
    List<Long> imageIds = images.stream().map(Image::getId).toList();
    List<ImageDraweTag> tags = imageDraweTagRepository.findByImageIdIn(imageIds);
    Map<Long, ImageDraweTag> tagMap =
        tags.stream().collect(Collectors.toMap(t -> t.getImage().getId(), Function.identity()));

    // 5. sourceId 키로 Image 매핑
    Map<String, Image> imageMap =
        images.stream().collect(Collectors.toMap(Image::getSourceId, Function.identity()));

    // 6. Pinecone 순위 유지하면서 ImageResult 조립
    List<ImageResult> results =
        matches.stream()
            .map(
                match -> {
                  Image img = imageMap.get(match.id());
                  if (img == null) {
                    log.warn("Pincone에 있지만 MySQL에 없는 ID: {}", match.id());
                    return null;
                  }
                  ImageDraweTag tag = tagMap.get(img.getId());

                  return new ImageResult(
                      img.getId(),
                      img.getSourceId(),
                      img.getUrl(),
                      img.getPhotographerUsername(),
                      img.getPhotographerName(),
                      match.score(),
                      tag != null ? tag.getTechnique() : null,
                      tag != null ? tag.getSubject() : null,
                      tag != null ? tag.getMood() : null,
                      tag != null ? tag.getUtility() : null,
                      tag != null ? tag.getFreeTags() : null,
                      img.getRawTags() != null ? img.getRawTags() : Collections.emptyList(),
                      img.getSource() != null ? img.getSource().name() : null);
                })
            .filter(r -> r != null)
            .toList();

    log.info("검색 완료: {}, 반환={}개", logCtx, results.size());
    return new SearchResponse(results, results.size(), echoQuery);
  }
}
