package com.drawe.backend.domain.onboarding;

import com.drawe.backend.domain.User;
import com.drawe.backend.domain.UserPrefTag;
import com.drawe.backend.domain.enums.Axis;
import com.drawe.backend.domain.onboarding.UserPrefTagRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 온보딩 선호 정보를 LLM 프롬프트에 인젝션할 텍스트 블록으로 변환.
 *
 * <p>축별로 weight 내림차순 top-N개를 뽑아 [사용자 선호] 블록 생성. 온보딩 안 한 사용자(prefs 비어있음)는 빈 문자열 반환하여 인젝션
 * skip되도록 한다.
 *
 * <p>사용처: ChatLlmService에서 system 프롬프트 빌드 시 호출.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPrefSummaryService {

    private static final int TOP_N_PER_AXIS = 3;

    private final UserPrefTagRepository userPrefTagRepository;

    /**
     * 사용자 선호 요약 블록 생성.
     *
     * @param user 대상 사용자
     * @return 인젝션할 텍스트 블록. 온보딩 안 한 사용자는 빈 문자열.
     */
    @Transactional(readOnly = true)
    public String buildSummary(User user) {
        List<UserPrefTag> prefs = userPrefTagRepository.findByUser(user);
        if (prefs.isEmpty()) {
            log.debug("사용자 선호 정보 없음: userId={}", user.getId());
            return "";
        }

        // 축별로 그룹핑 + weight 내림차순 top N
        Map<Axis, List<String>> topByAxis =
                prefs.stream()
                        .collect(
                                Collectors.groupingBy(
                                        UserPrefTag::getAxis,
                                        Collectors.collectingAndThen(
                                                Collectors.toList(),
                                                list ->
                                                        list.stream()
                                                                .sorted(
                                                                        Comparator.comparingInt(UserPrefTag::getWeight).reversed())
                                                                .limit(TOP_N_PER_AXIS)
                                                                .map(UserPrefTag::getValue)
                                                                .toList())));

        StringBuilder sb = new StringBuilder("[사용자 선호]\n");
        appendIfPresent(sb, "기법", topByAxis.get(Axis.AXIS_TECHNIQUE));
        appendIfPresent(sb, "주제", topByAxis.get(Axis.AXIS_SUBJECT));
        appendIfPresent(sb, "분위기", topByAxis.get(Axis.AXIS_MOOD));

        String result = sb.toString().trim();
        log.debug("선호 요약 생성: userId={}, 길이={}", user.getId(), result.length());
        return result;
    }

    private void appendIfPresent(StringBuilder sb, String label, List<String> values) {
        if (values == null || values.isEmpty()) return;
        sb.append("- ").append(label).append(": ").append(String.join(", ", values)).append("\n");
    }
}

