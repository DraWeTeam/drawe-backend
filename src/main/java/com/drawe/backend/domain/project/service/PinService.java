package com.drawe.backend.domain.project.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.ImageDraweTag;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.image.repository.ImageDraweTagRepository;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.domain.project.dto.PinItem;
import com.drawe.backend.domain.project.dto.PinListResponse;
import com.drawe.backend.domain.project.repository.ProjectRepository;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PinService {
  private static final int MAX_PIN_SLOTS = 3;

  private final ProjectRepository projectRepository;
  private final ImageRepository imageRepository;
  private final ImageDraweTagRepository imageDraweTagRepository;

  @Transactional
  public void addPins(User user, Long projectId, Long imageId) {
    Project project = loadAuthorized(user, projectId);

    if (!imageRepository.existsById(imageId)) {
      throw new CustomException(ErrorCode.NOT_FOUND);
    }

    List<Long> pins = project.getPinnedImageIds();
    if (pins == null) {
      pins = new ArrayList<>();
    }

    if (pins.contains(imageId)) {
      log.debug("이미 핀된 이미지: projectId={}, imageId={}", projectId, imageId);
      return;
    }

    if (pins.size() >= MAX_PIN_SLOTS) {
      throw new CustomException(ErrorCode.PIN_SLOT_FULL);
    }

    pins.add(imageId);
    project.setPinnedImageIds(pins);
    log.info("핀 추가: userId={}, projectId={}, imageId={}", user.getId(), projectId, imageId);
  }

  @Transactional
  public void removePin(User user, Long projectId, Long imageId) {
    Project project = loadAuthorized(user, projectId);

    List<Long> pins = project.getPinnedImageIds();
    if (pins == null || !pins.remove(imageId)) {
      log.debug("핀돼 있지 않은 이미지 해제 시도: projectId={}, imageId={}", projectId, imageId);
      return;
    }
    project.setPinnedImageIds(pins);
    log.info("핀 해제: userId={}, projectId={}, imageId={}", user.getId(), projectId, imageId);
  }

  @Transactional(readOnly = true)
  public PinListResponse getPins(User user, Long projectId) {
    Project project = loadAuthorized(user, projectId);

    List<Long> pinIds = project.getPinnedImageIds();
    if (pinIds == null || pinIds.isEmpty()) {
      return new PinListResponse(Collections.emptyList(), 0, MAX_PIN_SLOTS);
    }

    // 이미지 메타 조회
    List<Image> images = imageRepository.findAllById(pinIds);
    List<Long> imageIds = images.stream().map(Image::getId).toList();
    List<ImageDraweTag> tags = imageDraweTagRepository.findByImageIdIn(imageIds);
    Map<Long, ImageDraweTag> tagMap =
        tags.stream().collect(Collectors.toMap(t -> t.getImage().getId(), Function.identity()));

    // 사용자가 핀한 순서 유지
    Map<Long, Image> imageMap =
        images.stream().collect(Collectors.toMap(Image::getId, Function.identity()));

    List<PinItem> items =
        pinIds.stream()
            .map(imageMap::get)
            .filter(Objects::nonNull) // 삭제된 이미지 필터링
            .map(img -> toPinItem(img, tagMap.get(img.getId())))
            .toList();

    return new PinListResponse(items, items.size(), MAX_PIN_SLOTS);
  }

  private PinItem toPinItem(Image img, ImageDraweTag tag) {
    return new PinItem(
        img.getId(),
        img.getUrl(),
        img.getPhotographerName(),
        img.getPhotographerUsername(),
        img.getSource() != null ? img.getSource().name() : null,
        tag != null ? tag.getTechnique() : null,
        tag != null ? tag.getSubject() : null,
        tag != null ? tag.getMood() : null,
        img.getRawTags() != null ? img.getRawTags() : Collections.emptyList());
  }

  private Project loadAuthorized(User user, Long projectId) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    if (!project.getUser().getId().equals(user.getId())) {
      throw new CustomException(ErrorCode.FORBIDDEN);
    }
    return project;
  }
}
