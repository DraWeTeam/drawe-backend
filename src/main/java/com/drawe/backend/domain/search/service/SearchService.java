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
   */
  public SearchResponse search(SearchRequest request) {
    String query = request.query();
    int topK = request.getTopK();

    log.info("topK:{}", topK);

    // 1. 텍스트 -> 벡터
    List<Float> vector = fastApiClient.embedText(query);

    // 2. 벡터 -> Pinecone 검색
    List<PineconeMatch> matches = pineconeClient.queryByVector(vector, topK);
    if (matches.isEmpty()) {
      log.info("검색 결과 없음: query_length={}", query.length());
      return new SearchResponse(List.of(), 0, query);
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

    log.info("검색 완료: query_length={}, 반환={}개", query.length(), results.size());
    return new SearchResponse(results, results.size(), query);
  }
}
