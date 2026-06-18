package com.drawe.backend.domain.project.repository;

import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.ProjectReference;
import com.drawe.backend.domain.User;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectReferenceRepository extends JpaRepository<ProjectReference, Long> {

  long countByProject(Project project);

  void deleteByProject(Project project);

  /**
   * 레퍼런스 아카이브 — 한 유저의 모든 프로젝트 레퍼런스를 image 와 함께 한 번에 로드(N+1 방지).
   * 호출 측이 project 별로 그룹핑한다. 프로젝트 최신순, 그 안에서 추가 최신순(addedAt DESC).
   */
  @Query(
      "SELECT pr FROM ProjectReference pr "
          + "JOIN FETCH pr.image "
          + "JOIN FETCH pr.project p "
          + "WHERE p.user = :user "
          + "ORDER BY p.id DESC, pr.addedAt DESC")
  List<ProjectReference> findAllByUserWithImage(@Param("user") User user);
}
