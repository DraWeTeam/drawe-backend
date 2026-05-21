-- AI 생성 이미지의 추적/디버깅용 메타데이터.
-- - prompt: Bria에 보낸 영문 프롬프트 (디버깅·재학습 데이터)
-- - created_by_user_id: 생성한 사용자. 전체 공개라도 "내가 만든 이미지" UI나
--   감사 로그에 사용. 사용자 삭제 시 SET NULL로 이미지 자체는 보존.
-- - indexed_at: Pinecone 적재 완료 시각. NULL이면 미적재 → 추후 수동 재처리 대상.
ALTER TABLE `images`
  ADD COLUMN `prompt` text NULL AFTER `raw_tags`,
  ADD COLUMN `created_by_user_id` bigint NULL AFTER `prompt`,
  ADD COLUMN `indexed_at` datetime(6) NULL AFTER `created_by_user_id`,
  ADD CONSTRAINT `fk_images_created_by_user`
    FOREIGN KEY (`created_by_user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL,
  ADD KEY `idx_images_created_by` (`created_by_user_id`),
  ADD KEY `idx_images_indexed_at` (`indexed_at`);
