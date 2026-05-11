package com.drawe.backend.domain.feedback.repository;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.ImageFeedback;
import com.drawe.backend.domain.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageFeedbackRepository extends JpaRepository<ImageFeedback, Long> {
  Optional<ImageFeedback> findByUserAndImage(User user, Image image);
}
