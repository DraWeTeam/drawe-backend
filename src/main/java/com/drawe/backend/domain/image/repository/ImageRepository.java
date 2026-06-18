package com.drawe.backend.domain.image.repository;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.enums.ImageSource;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImageRepository extends JpaRepository<Image, Long> {

  // 여러 source_id 이미지를 한번에 조회
  List<Image> findBySourceIdIn(List<String> sourceIds);

  List<Image> findByIsOnboardingTrue();

  /**
   * 완성작 갤러리 — 특정 유저가 만든 AI 이미지를 최신순 페이징 조회 (source=AI AND created_by_user_id=user).
   * 정렬은 {@code createdAt DESC, id DESC} — V7 이전 적재분은 createdAt 이 NULL 이라 id 순으로 흐른다(JPQL
   * NULL 정렬은 DB 기본을 따르므로 id 보조키로 결정론적 순서를 보장한다).
   */
  @Query(
      "SELECT i FROM Image i WHERE i.source = :source AND i.createdBy = :createdBy "
          + "ORDER BY i.createdAt DESC, i.id DESC")
  Page<Image> findCompletedGallery(
      @Param("source") ImageSource source, @Param("createdBy") User createdBy, Pageable pageable);
}
