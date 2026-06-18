-- 완성작 갤러리 최신순 정렬을 위해 images 에 생성 시각 컬럼 추가.
-- 기존 행은 created_at 이 NULL — 정렬은 (created_at DESC, id DESC) 로 NULL 행을 id 순으로 흘린다.
-- 신규 행은 엔티티 @CreationTimestamp 가 채운다.
ALTER TABLE `images` ADD COLUMN `created_at` datetime(6) DEFAULT NULL;

-- 갤러리 조회 인덱스: 특정 유저의 AI 완성작을 created_at 순으로 자주 페이징 조회한다.
CREATE INDEX `idx_img_creator_created` ON `images` (`created_by_user_id`, `source`, `created_at`);
