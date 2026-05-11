package com.drawe.backend.domain.onboarding.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.ImageDraweTag;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.UserPrefTag;
import com.drawe.backend.domain.enums.Axis;
import com.drawe.backend.domain.image.repository.ImageDraweTagRepository;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.domain.onboarding.dto.OnboardingImage;

import java.util.*;

import com.drawe.backend.domain.onboarding.UserPrefTagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final ImageRepository imageRepository;
    private final ImageDraweTagRepository imageDraweTagRepository;
    private final UserPrefTagRepository userPrefTagRepository;

    /**
     * 사용자가 온보딩 완료했는지 여부.
     */
    @Transactional(readOnly = true)
    public boolean isCompleted(User user) {
        return userPrefTagRepository.existsByUser(user);
    }

    /**
     * 온보딩용 이미지 목록 반환 (is_onboarding=true).
     */
    @Transactional(readOnly = true)
    public List<OnboardingImage> getOnboardingImages() {
        List<Image> images = imageRepository.findByIsOnboardingTrue();

        if (images.isEmpty()) {
            log.warn("온보딩 이미지가 DB에 없습니다. is_onboarding=true인 이미지를 추가하세요.");
            return List.of();
        }

        List<Long> imageIds = images.stream().map(Image::getId).toList();
        List<ImageDraweTag> tags = imageDraweTagRepository.findByImageIdIn(imageIds);

        Map<Long, ImageDraweTag> tagMap = new HashMap<>();
        for (ImageDraweTag t : tags) {
            tagMap.put(t.getImage().getId(), t);
        }

        return images.stream()
                .map(img -> {
                    ImageDraweTag tag = tagMap.get(img.getId());
                    String label = buildLabel(tag);
                    return new OnboardingImage(
                            img.getId(),
                            img.getUrl(),
                            tag != null ? tag.getTechnique() : null,
                            tag != null ? tag.getSubject() : null,
                            tag != null ? tag.getMood() : null,
                            label
                    );
                })
                .toList();
    }

    /**
     * 사용자가 선택한 이미지로부터 선호 태그 저장.
     */
    @Transactional
    public void saveOnboarding(User user, List<Long> selectedImageIds) {
        if (selectedImageIds == null || selectedImageIds.isEmpty()) {
            log.info("온보딩 스킵: user={}", user.getId());
            return;
        }

        // 기존 user_pref_tags 삭제 (재온보딩 케이스)
        userPrefTagRepository.deleteByUser(user);

        // 선택한 이미지들의 태그 가져오기
        List<ImageDraweTag> selectedTags =
                imageDraweTagRepository.findByImageIdIn(selectedImageIds);

        // 태그별 빈도 카운트 (선택된 횟수가 곧 weight)
        Map<Axis, Map<String, Integer>> tagCounts = new HashMap<>();
        tagCounts.put(Axis.AXIS_TECHNIQUE, new HashMap<>());
        tagCounts.put(Axis.AXIS_SUBJECT, new HashMap<>());
        tagCounts.put(Axis.AXIS_MOOD, new HashMap<>());

        for (ImageDraweTag tag : selectedTags) {
            countTag(tagCounts.get(Axis.AXIS_TECHNIQUE), tag.getTechnique());
            countTag(tagCounts.get(Axis.AXIS_SUBJECT), tag.getSubject());
            countTag(tagCounts.get(Axis.AXIS_MOOD), tag.getMood());
        }

        // user_pref_tags 저장
        List<UserPrefTag> prefs = new ArrayList<>();
        for (Map.Entry<Axis, Map<String, Integer>> axisEntry : tagCounts.entrySet()) {
            for (Map.Entry<String, Integer> valueEntry : axisEntry.getValue().entrySet()) {
                UserPrefTag pref = new UserPrefTag();
                pref.setUser(user);
                pref.setAxis(axisEntry.getKey());
                pref.setValue(valueEntry.getKey());
                pref.setWeight(valueEntry.getValue());
                prefs.add(pref);
            }
        }
        userPrefTagRepository.saveAll(prefs);

        log.info("온보딩 완료: user={}, 선택 {}개, 저장된 선호 {}개",
                user.getId(), selectedImageIds.size(), prefs.size());
    }

    private void countTag(Map<String, Integer> counter, String value) {
        if (value == null || value.isBlank()) return;
        counter.merge(value, 1, Integer::sum);
    }

    private String buildLabel(ImageDraweTag tag) {
        if (tag == null) return "이미지";
        List<String> parts = new ArrayList<>();
        if (tag.getTechnique() != null) parts.add(tag.getTechnique());
        if (tag.getSubject() != null) parts.add(tag.getSubject());
        if (tag.getMood() != null) parts.add(tag.getMood());
        return parts.isEmpty() ? "이미지" : String.join(" · ", parts);
    }
}