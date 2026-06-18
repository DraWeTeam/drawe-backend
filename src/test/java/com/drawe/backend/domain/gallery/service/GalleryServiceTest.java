package com.drawe.backend.domain.gallery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.ProjectReference;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.gallery.dto.ReferenceArchiveResponse;
import com.drawe.backend.domain.image.repository.ImageRepository;
import com.drawe.backend.domain.image.service.ImageUrlSigner;
import com.drawe.backend.domain.project.repository.ProjectReferenceRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GalleryService 단위 테스트 — 레퍼런스 아카이브의 프로젝트별 그룹핑 로직 검증. */
class GalleryServiceTest {

  private final ImageRepository imageRepository = mock(ImageRepository.class);
  private final ProjectReferenceRepository projectReferenceRepository =
      mock(ProjectReferenceRepository.class);
  private final ImageUrlSigner signer = mock(ImageUrlSigner.class);

  private final GalleryService service =
      new GalleryService(imageRepository, projectReferenceRepository, signer);

  private static Project project(long id, String name) {
    Project p = new Project();
    p.setId(id);
    p.setName(name);
    return p;
  }

  private static Image image(long id, String url) {
    Image img = new Image();
    img.setId(id);
    img.setUrl(url);
    return img;
  }

  private static ProjectReference ref(Project p, Image img) {
    ProjectReference pr = mock(ProjectReference.class);
    when(pr.getProject()).thenReturn(p);
    when(pr.getImage()).thenReturn(img);
    return pr;
  }

  @Test
  @DisplayName("레퍼런스 아카이브 — 프로젝트별로 섹션이 묶이고 쿼리 순서를 보존한다")
  void groupsReferencesByProject() {
    User user = User.builder().id(1L).build();
    Project p1 = project(10L, "프로젝트A");
    Project p2 = project(20L, "프로젝트B");
    // ref(...) 안에서 mock stubbing 이 일어나므로 thenReturn 인자 안에서 직접 호출하면 Mockito 가
    // stubbing 중첩으로 오인한다(UnfinishedStubbing). 먼저 변수로 만든 뒤 thenReturn 에 넣는다.
    ProjectReference r1 = ref(p2, image(101L, "u101"));
    ProjectReference r2 = ref(p2, image(102L, "u102"));
    ProjectReference r3 = ref(p1, image(201L, "u201"));
    // 쿼리는 (project.id DESC, addedAt DESC) 정렬 — p2(20) 먼저, 그 안에서 순서대로.
    when(projectReferenceRepository.findAllByUserWithImage(user))
        .thenReturn(List.of(r1, r2, r3));
    // signer 는 입력 url 을 그대로 반환하도록 stub (서명 변환은 검증 대상 아님).
    when(signer.sign("u101")).thenReturn("u101");
    when(signer.sign("u102")).thenReturn("u102");
    when(signer.sign("u201")).thenReturn("u201");

    ReferenceArchiveResponse res = service.getReferenceArchive(user);

    // 섹션 2개, 등장 순서(p2 먼저) 보존
    assertThat(res.sections()).hasSize(2);
    assertThat(res.sections().get(0).projectId()).isEqualTo(20L);
    assertThat(res.sections().get(0).projectName()).isEqualTo("프로젝트B");
    assertThat(res.sections().get(0).references()).hasSize(2);
    assertThat(res.sections().get(0).references().get(0).imageId()).isEqualTo(101L);
    // 두번째 섹션 = p1, 레퍼런스 1개
    assertThat(res.sections().get(1).projectId()).isEqualTo(10L);
    assertThat(res.sections().get(1).references()).hasSize(1);
    assertThat(res.sections().get(1).references().get(0).imageId()).isEqualTo(201L);
  }

  @Test
  @DisplayName("레퍼런스 없으면 빈 섹션 목록")
  void emptyWhenNoReferences() {
    User user = User.builder().id(1L).build();
    when(projectReferenceRepository.findAllByUserWithImage(user)).thenReturn(List.of());

    ReferenceArchiveResponse res = service.getReferenceArchive(user);

    assertThat(res.sections()).isEmpty();
  }
}
