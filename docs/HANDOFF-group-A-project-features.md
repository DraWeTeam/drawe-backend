# 인수인계 — 그룹 A: 프로젝트 기능 확장 (정렬 + 수정 + 키워드칩 + 마감일 필드)

**작성:** 2026-06-18 / **기준 브랜치:** `feature/SCRUM-96-AI-pipeline-A`
**대상:** 이 메모를 받는 클로드가 **새 브랜치**(사용자가 직접 생성)에서 그룹 A를 구현한다.

---

## 0. 이 작업이 뭔가 (스코프)

프로젝트 도메인 4개 기능을 **하나의 브랜치 + V7 마이그레이션 1개**로 묶어 처리한다. 전부 `projects` 테이블/Project 엔티티/ProjectController·Service를 건드려 서로 의존하므로 함께 한다.

| # | 기능 | 규모 |
|---|---|---|
| 1 | 리스트 정렬 (최근활동/만든날짜/이름) | 소 |
| 2 | 프로젝트 수정에 표지·키워드 필드 추가 | 소~중 |
| 3 | 키워드 칩 (List\<String\>) | 중 |
| 4 | **마감일(deadline) 필드만** (캘린더 자동추가는 그룹 C, 여기선 제외) | 소 |

**제외 (그룹 A 아님):** 제목 자동생성(LLM, 그룹 C), 통합검색(그룹 C), 갤러리/아카이브(그룹 B), 캘린더 자동추가(그룹 C).

---

## 1. 선행 사실 (조사 완료 — 현재 코드 상태)

### Project 엔티티 (`src/main/java/com/drawe/backend/domain/Project.java`)
현재 필드: id, user, name(100), subject(100), description(Lob), technique(30), mood(30), status(enum), pinnedImageIds(JSON), drawingUrl(500), detailAnswers(JSON Map), suggestionsShown, createdAt, updatedAt.
- **없는 필드 = 이번에 추가:** `keywords`(List\<String\>), `coverImageUrl`(표지), `deadline`(마감일).
- ⚠️ `subject/technique/mood`는 **단일 문자열**이라 키워드 칩(배열) 아님. 키워드는 신규 `keywords` 컬럼.
- ⚠️ 표지: 현재 `drawingUrl`(사용자 그림)·`pinnedImageIds`(핀)만 있음. 표지 전용 `coverImageUrl` 신규.

### 마이그레이션
- 최신 = `V6__images_source_enum_add_ai.sql`. **다음은 `V7`.** 경로: `src/main/resources/db/migration/`.
- Project는 JSON 컬럼에 `@JdbcTypeCode(SqlTypes.JSON)` 패턴을 씀(pinnedImageIds 참고, Project.java:62-64). keywords도 동일 패턴 권장.

### ProjectController (`domain/project/controller/ProjectController.java`)
- POST `/projects`(:36 create), GET `/projects`(:44 list — status/limit/offset만, **sort 없음**), GET `/{id}`(:53), PATCH `/{id}`(:59 update), DELETE(:68).

### ProjectService (`domain/project/service/ProjectService.java`)
- `create()` — request 필드 그대로 set 후 save.
- `getList()` — `projectRepository.findPage(user, status, limit, offset)` 호출. **정렬 인자 없음.**
- `update()` — null 체크 후 set하는 패턴. 현재 name/subject/technique/mood/description/status/detailAnswers 처리. **여기에 keywords/coverImageUrl/deadline 분기 추가.**

### 정렬 하드코딩 위치 (`domain/project/repository/ProjectRepositoryImpl.java`)
- `findPage()`의 JPQL에 `ORDER BY p.createdAt DESC` **하드코딩**(2곳). 여기를 sort 파라미터로 분기.

### DTO (`domain/project/dto/`)
- `CreateProjectRequest`(record): name(필수)/subject(필수)/technique/mood/description.
- `UpdateProjectRequest`(record): name/subject/technique/mood/description/status/detailAnswers (전부 nullable=부분수정).
- `ProjectDetailResponse`, `ProjectListResponse`, `ProjectListItem` — 응답에 새 필드 노출하려면 여기도 수정.

---

## 2. 구현 체크리스트 (권장 순서)

### Phase 1 — 마이그레이션 + 엔티티 (먼저, 한 번에)
- [ ] `V7__projects_add_keywords_cover_deadline.sql`:
  - `ALTER TABLE projects ADD COLUMN keywords JSON NULL;`
  - `ALTER TABLE projects ADD COLUMN cover_image_url VARCHAR(500) NULL;`
  - `ALTER TABLE projects ADD COLUMN deadline DATETIME NULL;` (또는 DATE — 시간 필요 여부로 결정. 캘린더 그룹 C 고려하면 DATETIME 권장)
- [ ] Project.java 필드 추가:
  - `keywords`: `@JdbcTypeCode(SqlTypes.JSON) @Column(name="keywords", columnDefinition="JSON") private List<String> keywords = new ArrayList<>();`
  - `coverImageUrl`: `@Size(max=500) @Column(name="cover_image_url", length=500) private String coverImageUrl;`
  - `deadline`: `@Column(name="deadline") private Instant deadline;` (엔티티 시간타입은 기존이 Instant 통일 — createdAt/updatedAt 참고)

### Phase 2 — 정렬 (#1)
- [ ] ProjectController GET `/projects`에 `@RequestParam(defaultValue="recent") String sort` 추가.
- [ ] sort 허용값 정의: `recent`(updatedAt DESC = 최근활동), `created`(createdAt DESC = 만든날짜), `name`(name ASC = 이름). **화이트리스트로만** 받기(JPQL injection 방지 — enum이나 switch로 ORDER BY 절 매핑, 사용자 문자열 직접 삽입 금지).
- [ ] ProjectService.getList 시그니처에 sort 추가 → findPage로 전달.
- [ ] ProjectRepositoryImpl.findPage: `ORDER BY` 하드코딩을 sort에 따라 분기. (JPQL 문자열 조립 시 sort는 화이트리스트 검증된 값만.)

### Phase 3 — 키워드칩 + 표지 + 마감일 수정 API (#2#3#4)
- [ ] UpdateProjectRequest record에 `List<String> keywords`, `String coverImageUrl`, `Instant deadline` 추가 (nullable=부분수정 유지).
  - ⚠️ 부분수정 패턴 주의: keywords를 "빈 리스트로 비우기" vs "수정 안 함(null)"을 구분해야 함. null=수정안함, 빈 리스트=전부 삭제로 처리 권장. (기존 코드는 `!= null` 체크만 하므로 동일 패턴.)
- [ ] ProjectService.update에 분기 추가 (기존 null 체크 패턴 그대로):
  ```java
  if (request.keywords() != null) project.setKeywords(request.keywords());
  if (request.coverImageUrl() != null) project.setCoverImageUrl(request.coverImageUrl());
  if (request.deadline() != null) project.setDeadline(request.deadline());
  ```
- [ ] CreateProjectRequest에도 keywords/coverImageUrl/deadline 추가할지 결정 (생성 시점에 받을지 — 보통 받게 함). 받으면 create()에도 set 추가.
- [ ] 키워드 칩 개수/길이 제한 검증 (예: 최대 10개, 각 20자). DTO에 커스텀 검증 또는 서비스에서.

### Phase 4 — 응답 DTO 노출
- [ ] ProjectDetailResponse.from / ProjectListItem.of 에 keywords/coverImageUrl/deadline 필드 추가 (프론트가 보게).

### Phase 5 — 테스트 + 검증
- [ ] ProjectService 단위 테스트: 정렬 3종, keywords 부분수정(null=유지, []=비움), deadline set.
- [ ] 컴파일: `export JAVA_HOME="/c/Program Files/Java/jdk-23" && ./gradlew compileJava compileTestJava`
- [ ] Flyway: 로컬 docker로 V7 적용되는지 (`docker compose up -d --build backend` 후 부팅 로그 flyway migrate 확인).

---

## 3. 환경 함정 (이 프로젝트 공통 — 반드시 지킬 것)

- **JDK:** `JAVA_HOME="/c/Program Files/Java/jdk-23"` 명시. 비우면 JDK25로 죽음.
- **gradle test-results 잠금(Windows):** test 전 `./gradlew --stop` + `rm -rf build/test-results build/reports`.
- **커밋 메시지:** 한글이면 `-F <file>` 또는 heredoc(Bash). 끝에 `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **선별 git add:** `.env`(gitignore)·`docs/HANDOFF-*` 등 무관 변경 섞지 말고 작업 파일만 stage.
- **마이그레이션 불변성:** V7은 한번 적용/공유되면 수정 금지. 로컬에서만 돌렸으면 고쳐도 되지만, push 후엔 V8로.

## 4. 의존성·주의

- **이메일 인증 합치기와 독립:** B 개발자의 이메일 회원가입 인증 기능은 현재 origin 어디에도 push 안 됨(develop 포함 전수 확인). 그룹 A는 auth 도메인을 안 건드리므로 **무관하게 진행 가능.** 인증 기능이 나중에 push되면 그때 별도로 합치면 됨.
- **그룹 B(갤러리)·C(제목자동생성/통합검색/캘린더)와의 관계:** 마감일 "필드"는 여기, "캘린더 자동추가"는 그룹 C. deadline 컬럼을 DATETIME으로 둬야 그룹 C 캘린더 연동이 수월.
- **표지(coverImageUrl) 채우는 소스:** 이번엔 필드+수정 API만. 표지 이미지를 어디서 고르는지(완성작 중 선택? 업로드?)는 프론트/그룹 B와 연계 — 일단 URL 받는 것까지만.

## 5. 참고 문서
- 8개 기능 전체 분류·규모: 이 세션 대화(그룹 A/B/C 추천)
- 마이그레이션 baseline DDL: `V1__baseline.sql` (projects 테이블 정의)
