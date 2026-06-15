package com.drawe.backend.domain.llm.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import kr.co.shineware.nlp.komoran.constant.DEFAULT_MODEL;
import kr.co.shineware.nlp.komoran.core.Komoran;
import kr.co.shineware.nlp.komoran.model.Token;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Komoran 기반 한글 키워드 추출기.
 *
 * <p><strong>현재 단계: [4/4] Micrometer 메트릭 추가 — 완성</strong>
 *
 * <p><strong>전체 흐름:</strong>
 * <pre>
 *   cleanedMessage
 *      ↓ Komoran 형태소 분석
 *   Token 리스트
 *      ↓ [필터1] CONTENT_TAGS (+ 'X하다' 무드 형용사 어근 XR+XSA 조건부 — {@link #isContentStem})
 *      ↓ [필터2] STOPWORDS 제거
 *      ↓ [필터3] 사전 매핑 → metric: drawe.dict.lookup
 *   hits / misses
 *      ↓ 미스율 측정
 *      ├─ ≤ 30%: hits 반환
 *      └─ > 30%: LLM 폴백 → metric: drawe.komoran.fallback
 *   (전체 처리 시간 → metric: drawe.komoran.extract)
 * </pre>
 *
 * <p><strong>메트릭:</strong>
 * <ul>
 *   <li>{@code drawe.dict.lookup} (counter, tag: {@code result=hit|miss}) — 사전 적중률</li>
 *   <li>{@code drawe.komoran.fallback} (counter) — LLM 폴백 호출 빈도</li>
 *   <li>{@code drawe.komoran.extract} (timer) — extract() 처리 시간</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KomoranKeywordExtractor {

    private static final String USER_DIC_RESOURCE = "komoran-user-dic.txt";

    /**
     * 검색 키워드 후보 품사. 이 외에 'X하다/X스럽다' 묘사 형용사 어근(XR + 뒤따르는 XSA)도
     * {@link #isContentStem} 에서 추가로 추출한다 — '따뜻/XR + 하/XSA'(warm) 같은 무드 형용사.
     */
    private static final Set<String> CONTENT_TAGS = Set.of("NNG", "NNP", "VV", "VA");

    /** 의미 없는 흔한 단어 — 사전 매핑 전에 제거. */
    private static final Set<String> STOPWORDS = Set.of(
            "있", "없", "되", "하", "주", "보이", "알리", "받",
            "거", "게", "것", "수",
            "그렇", "어떻", "이렇", "저렇",
            "때", "곳", "번",
            // 검색/생성 요청 메타동사 — 시각 키워드가 아니라 노이즈. 어간에 남으면 사전 미스율을
            // 부풀려 불필요한 LLM 폴백을 유발한다. 예: "고양이 찾아줘" → 찾 제거 → [cat] (폴백 X).
            // "그리/그려/그렸"(그리다=draw)도 요청 동사라 제외 — '주요 키워드만 추출' 방침(2026-06-15).
            // 명사 "그림"(그림/NNG)은 별개 형태소라 영향 없음(user-dic 에 NNG 등록됨). 오분석형 "그렇"은 위에 이미 포함.
            "찾", "만들", "원하", "그리", "그려", "그렸"
    );

    /** 사전 미스율 임계 — 이 비율 초과 시 LLM 폴백 발동. */
    private static final double LLM_FALLBACK_THRESHOLD = 0.30;
    private static final int LLM_FALLBACK_THRESHOLD_PCT = (int) (LLM_FALLBACK_THRESHOLD * 100);

    // 메트릭 이름
    private static final String METRIC_LOOKUP = "drawe.dict.lookup";
    private static final String METRIC_FALLBACK = "drawe.komoran.fallback";
    private static final String METRIC_EXTRACT = "drawe.komoran.extract";
    private static final String TAG_RESULT = "result";
    private static final String VALUE_HIT = "hit";
    private static final String VALUE_MISS = "miss";

    private final ArtTermsDictionary dictionary;
    private final KeywordExtractorFallback fallback;
    private final MeterRegistry meterRegistry;

    // 자주 사용되는 카운터·타이머 캐싱 (warm-up + 가독성)
    private Counter hitCounter;
    private Counter missCounter;
    private Counter fallbackCounter;
    private Timer extractTimer;

    private Komoran komoran;

    @PostConstruct
    public void init() {
        long start = System.currentTimeMillis();
        try {
            this.komoran = new Komoran(DEFAULT_MODEL.FULL);
            loadUserDictionary();

            // 메트릭 미리 생성 (Prometheus 에 0 값으로 노출 → 그래프 안 깨짐)
            this.hitCounter = meterRegistry.counter(METRIC_LOOKUP, TAG_RESULT, VALUE_HIT);
            this.missCounter = meterRegistry.counter(METRIC_LOOKUP, TAG_RESULT, VALUE_MISS);
            this.fallbackCounter = meterRegistry.counter(METRIC_FALLBACK);
            this.extractTimer = meterRegistry.timer(METRIC_EXTRACT);

            long elapsed = System.currentTimeMillis() - start;
            log.info("KomoranKeywordExtractor initialized in {}ms", elapsed);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Komoran", e);
        }
    }

    private void loadUserDictionary() throws IOException {
        ClassPathResource resource = new ClassPathResource(USER_DIC_RESOURCE);
        if (!resource.exists()) {
            log.warn("User dictionary not found in classpath: {} — running with default Komoran dictionary only",
                    USER_DIC_RESOURCE);
            return;
        }

        File tempFile = File.createTempFile("komoran-user-dic", ".txt");
        tempFile.deleteOnExit();

        try (InputStream is = resource.getInputStream()) {
            Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        komoran.setUserDic(tempFile.getAbsolutePath());

        long entryCount = countDictionaryEntries(tempFile);
        log.info("Komoran user dictionary loaded: {} entries from {}",
                entryCount, USER_DIC_RESOURCE);
    }

    private long countDictionaryEntries(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .count();
        }
    }

    /**
     * 형태소 분석 raw 결과 반환 (디버깅·테스트용).
     */
    public List<Token> analyze(String cleanedMessage) {
        if (cleanedMessage == null || cleanedMessage.isBlank()) {
            return List.of();
        }
        return komoran.analyze(cleanedMessage).getTokenList();
    }

    /**
     * 한글 메시지 → 영문 검색 키워드 추출.
     *
     * <p>3중 필터 + 미스율 기반 LLM 폴백 + 메트릭.
     *
     * @param cleanedMessage A의 TextPreprocessor 출력
     * @return 영문 키워드 리스트 (중복 제거됨), 빈 입력이면 빈 리스트
     */
    public List<String> extract(String cleanedMessage) {
        if (cleanedMessage == null || cleanedMessage.isBlank()) {
            return List.of();
        }
        return extractTimer.record(() -> doExtract(cleanedMessage));
    }

    /**
     * 검색 키워드 후보 어간인지 판정. 기본은 {@link #CONTENT_TAGS}(NNG/NNP/VV/VA). 추가로 'X하다/X스럽다'
     * 묘사 형용사의 어근(XR)은 <b>바로 뒤에 형용사 파생 접미사(XSA)가 올 때만</b> 포함한다 —
     * '따뜻/XR + 하/XSA'(warm)·'화사/XR + 하/XSA'(bright) 같은 무드 형용사를 잡되, XR 단독이나
     * 동사 파생('운동하다'의 하/XSV) 등 비형용사 노이즈는 배제한다(정교 추출). X 가 NNG 인 'X하다'는
     * 어차피 NNG 로 따로 잡히므로 영향 없다.
     */
    private static boolean isContentStem(Token token, int idx, List<Token> tokens) {
        String pos = token.getPos();
        if (CONTENT_TAGS.contains(pos)) {
            return true;
        }
        return "XR".equals(pos)
                && idx + 1 < tokens.size()
                && "XSA".equals(tokens.get(idx + 1).getPos());
    }

    private List<String> doExtract(String cleanedMessage) {
        // 1~2. 품사 필터(+ XR+XSA 무드 형용사) + 스톱워드 제거 → 어간 후보
        List<Token> tokens = analyze(cleanedMessage);
        List<String> stems = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (!isContentStem(token, i, tokens)) {
                continue;
            }
            String morph = token.getMorph();
            if (!STOPWORDS.contains(morph)) {
                stems.add(morph);
            }
        }

        if (stems.isEmpty()) {
            log.debug("No stems after filter for message: '{}'", cleanedMessage);
            return List.of();
        }

        // 3. 사전 매핑 + 미스 카운트 (+ 메트릭)
        List<String> hits = new ArrayList<>();
        int misses = 0;

        for (String stem : stems) {
            Optional<String> en = dictionary.lookup(stem);
            if (en.isPresent()) {
                hits.add(en.get());
                hitCounter.increment();
            } else {
                misses++;
                missCounter.increment();
                log.debug("Dictionary miss: '{}'", stem);
            }
        }

        // 4. 미스율 측정 + 폴백 분기
        double missRate = (double) misses / stems.size();

        if (missRate > LLM_FALLBACK_THRESHOLD) {
            log.info("Dict miss rate {}% > {}% — fallback to LLM. message='{}'",
                    Math.round(missRate * 100),
                    LLM_FALLBACK_THRESHOLD_PCT,
                    cleanedMessage);
            fallbackCounter.increment();
            return fallback.extract(cleanedMessage);
        }

        return hits.stream().distinct().toList();
    }
}