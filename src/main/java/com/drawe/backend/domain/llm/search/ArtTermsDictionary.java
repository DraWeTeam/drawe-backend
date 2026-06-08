package com.drawe.backend.domain.llm.search;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 미술 도메인 한영 사전.
 *
 * <p>Komoran 형태소 분석 후의 한글 어간을 영문 CLIP 검색어로 매핑한다.
 * 사전 미스 처리(폴백·로깅)는 호출 측({@code KomoranKeywordExtractor})이 책임진다.
 *
 * <p>파일: {@code src/main/resources/art-terms-ko-en.csv}
 * <ul>
 *   <li>형식: {@code ko,en,category}</li>
 *   <li>{@code #}로 시작하는 줄은 주석으로 무시</li>
 *   <li>빈 줄 무시</li>
 *   <li>첫 데이터 줄은 헤더로 간주하여 건너뜀</li>
 *   <li>UTF-8 인코딩 (BOM 허용)</li>
 * </ul>
 *
 * <p>스프링 부트 시작 시 1회 로드 후 읽기 전용. 갱신은 애플리케이션 재시작으로.
 */
@Slf4j
@Component
public class ArtTermsDictionary {

    private static final String RESOURCE_PATH = "art-terms-ko-en.csv";
    private static final String EXPECTED_HEADER = "ko,en,category";
    private static final char UTF8_BOM = '\uFEFF';

    private Map<String, String> koToEn = Collections.emptyMap();
    private Map<String, String> koToCategory = Collections.emptyMap();

    @PostConstruct
    public void load() {
        Map<String, String> enMap = new HashMap<>();
        Map<String, String> catMap = new HashMap<>();

        int loaded = 0;
        int skipped = 0;
        int duplicates = 0;

        try (InputStream is = new ClassPathResource(RESOURCE_PATH).getInputStream();
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            boolean headerSkipped = false;
            int lineNumber = 0;

            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // BOM 제거 (첫 줄에만)
                if (lineNumber == 1 && !line.isEmpty() && line.charAt(0) == UTF8_BOM) {
                    line = line.substring(1);
                }

                String trimmed = line.trim();

                // 빈 줄 / 주석 스킵
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                // 첫 데이터 줄 = 헤더
                if (!headerSkipped) {
                    headerSkipped = true;
                    if (!trimmed.startsWith(EXPECTED_HEADER)) {
                        log.warn("Expected header '{}' but got: '{}' at line {}",
                                EXPECTED_HEADER, trimmed, lineNumber);
                    }
                    continue;
                }

                // 데이터 파싱
                String[] parts = trimmed.split(",", -1);
                if (parts.length < 3) {
                    log.warn("Skipping malformed line {}: '{}'", lineNumber, trimmed);
                    skipped++;
                    continue;
                }

                String ko = parts[0].trim();
                String en = parts[1].trim();
                String category = parts[2].trim();

                if (ko.isEmpty() || en.isEmpty()) {
                    log.warn("Skipping line {} with empty ko or en: '{}'", lineNumber, trimmed);
                    skipped++;
                    continue;
                }

                if (enMap.containsKey(ko)) {
                    log.warn("Duplicate key '{}' at line {}, overwriting previous value '{}'",
                            ko, lineNumber, enMap.get(ko));
                    duplicates++;
                }

                enMap.put(ko, en);
                catMap.put(ko, category);
                loaded++;
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to load art terms dictionary: " + RESOURCE_PATH, e);
        }

        // 로드 완료 후 불변 맵으로 교체 (스레드 안전)
        this.koToEn = Map.copyOf(enMap);
        this.koToCategory = Map.copyOf(catMap);

        log.info("ArtTermsDictionary loaded: {} entries (skipped={}, duplicates={})",
                loaded, skipped, duplicates);
    }

    /**
     * 한글 어간 → 영문 검색어.
     *
     * @param ko Komoran 형태소 분석 결과의 어간 (예: "수채화", "달리", "앉")
     * @return 매핑된 영문, 없으면 {@code Optional.empty()}
     */
    public Optional<String> lookup(String ko) {
        if (ko == null || ko.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(koToEn.get(ko));
    }

    /**
     * 한글 어간 → 카테고리.
     *
     * @param ko Komoran 어간
     * @return 카테고리 (technique·subject·concept·...), 없으면 {@code Optional.empty()}
     */
    public Optional<String> getCategory(String ko) {
        if (ko == null || ko.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(koToCategory.get(ko));
    }

    /**
     * 사전 크기 (모니터링용).
     */
    public int size() {
        return koToEn.size();
    }
}
