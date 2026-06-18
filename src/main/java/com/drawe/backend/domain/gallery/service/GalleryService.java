package com.drawe.backend.domain.gallery.service;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.ProjectReference;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.enums.ImageSource;
import com.drawe.backend.domain.gallery.dto.GalleryItem;
import com.drawe.backend.domain.gallery.dto.GalleryResponse;
import com.drawe.backend.domain.gallery.dto.ReferenceArchiveResponse;
import com.drawe.backend.domain.gallery.dto.ReferenceArchiveResponse.ProjectSection;
import com.drawe.backend.domain.gallery.dto.ReferenceArchiveResponse.ReferenceImageItem;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.domain.image.service.ImageUrlSigner;
import com.drawe.backend.domain.project.repository.ProjectReferenceRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 완성작 갤러리 + 레퍼런스 아카이브 — 로그인 유저의 AI 완성작·프로젝트 레퍼런스를 조회한다. */
@Service
@RequiredArgsConstructor
public class GalleryService {

  private final ImageRepository imageRepository;
  private final ProjectReferenceRepository projectReferenceRepository;
  private final ImageUrlSigner imageUrlSigner;

  @Transactional(readOnly = true)
  public GalleryResponse getCompleted(User user, int page, int size) {
    Page<Image> result =
        imageRepository.findCompletedGallery(ImageSource.AI, user, PageRequest.of(page, size));

    var items = result.getContent().stream().map(i -> GalleryItem.of(i, imageUrlSigner)).toList();
    boolean hasMore = result.hasNext();
    return new GalleryResponse(items, result.getTotalElements(), hasMore);
  }

  /** 레퍼런스 아카이브 — 유저의 모든 프로젝트 레퍼런스를 프로젝트별 섹션으로 묶는다. */
  @Transactional(readOnly = true)
  public ReferenceArchiveResponse getReferenceArchive(User user) {
    List<ProjectReference> refs = projectReferenceRepository.findAllByUserWithImage(user);

    // 쿼리가 (project.id DESC, addedAt DESC) 로 정렬돼 오므로 LinkedHashMap 으로 그 순서를 보존한다.
    Map<Long, ProjectSection> sections = new LinkedHashMap<>();
    for (ProjectReference ref : refs) {
      Project project = ref.getProject();
      ProjectSection section =
          sections.computeIfAbsent(
              project.getId(),
              id -> new ProjectSection(id, project.getName(), new ArrayList<>()));
      Image image = ref.getImage();
      section.references().add(new ReferenceImageItem(image.getId(), imageUrlSigner.sign(image.getUrl())));
    }
    return new ReferenceArchiveResponse(new ArrayList<>(sections.values()));
  }
}
