package com.drawe.backend.domain.feedback.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.ImageFeedback;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.enums.FeedbackType;
import com.drawe.backend.domain.feedback.dto.FeedbackResponse;
import com.drawe.backend.domain.feedback.repository.ImageFeedbackRepository;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageFeedbackService {

  private final ImageFeedbackRepository feedbackRepository;
  private final ImageRepository imageRepository;

  @Transactional
  public void saveFeedback(User user, Long imageId, FeedbackType type) {
    Image image =
        imageRepository
            .findById(imageId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

    // 기존 피드백 있으면 업데이트, 없으면 새로 생성
    ImageFeedback feedback =
        feedbackRepository
            .findByUserAndImage(user, image)
            .orElseGet(
                () -> {
                  ImageFeedback f = new ImageFeedback();
                  f.setUser(user);
                  f.setImage(image);
                  return f;
                });

    feedback.setFeedback(type);
    feedbackRepository.save(feedback);

    log.info("피드백 저장: user={}, image={}, type={}", user.getId(), imageId, type);
  }

  @Transactional
  public void removeFeedback(User user, Long imageId) {
    Image image =
        imageRepository
            .findById(imageId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

    feedbackRepository.findByUserAndImage(user, image).ifPresent(feedbackRepository::delete);

    log.info("피드백 제거: user={}, image={}", user.getId(), imageId);
  }

  @Transactional(readOnly = true)
  public FeedbackResponse getFeedback(User user, Long imageId) {
    Image image =
        imageRepository
            .findById(imageId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

    return feedbackRepository
        .findByUserAndImage(user, image)
        .map(f -> new FeedbackResponse(f.getFeedback()))
        .orElse(new FeedbackResponse(null)); // 피드백 없음
  }
}
