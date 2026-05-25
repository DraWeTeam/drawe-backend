package com.drawe.backend.domain.log;

import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.search.dto.ImageResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchLogService {

  private final SearchLogRepository searchLogRepository;

  /** 검색 로그를 비동기로 저장. 응답 속도에 영향 주지 않습니다. */
  @Async
  @Transactional
  public void log(
      User user,
      Project project,
      String originalMessage,
      String extractedKeywords,
      List<ImageResult> results,
      String source) {
    try {
      SearchLog logEntry = new SearchLog();

      logEntry.setUser(user);
      logEntry.setProject(project);
      logEntry.setOriginalMessage(truncate(originalMessage, 1000));
      logEntry.setExtractedKeywords(truncate(extractedKeywords, 500));
      logEntry.setResultCount(results.size());
      logEntry.setAvgScore(calculateAvgScore(results));
      logEntry.setSource(source);

      searchLogRepository.save(logEntry);

    } catch (Exception e) {
      // 검색 로그 저장 실패해도 응답에 영향 없음
      log.warn("검색 로그 저장 실패: error_class={}", e.getClass().getSimpleName());
    }
  }

  private Double calculateAvgScore(List<ImageResult> results) {
    if (results == null || results.isEmpty()) {
      return null;
    }
    return results.stream().mapToDouble(r -> r.score().doubleValue()).average().orElse(0.0);
  }

  private String truncate(String originalMessage, int maxLength) {
    if (originalMessage == null) {
      return null;
    }
    return originalMessage.length() > maxLength
        ? originalMessage.substring(0, maxLength)
        : originalMessage;
  }
}
