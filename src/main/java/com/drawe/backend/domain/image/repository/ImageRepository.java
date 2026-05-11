package com.drawe.backend.domain.image.repository;

import com.drawe.backend.domain.Image;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageRepository extends JpaRepository<Image, Long> {

  // 여러 source_id 이미지를 한번에 조회
  List<Image> findBySourceIdIn(List<String> sourceIds);
  List<Image> findByIsOnboardingTrue();
}
