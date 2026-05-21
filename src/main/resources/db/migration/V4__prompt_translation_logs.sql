-- 한국어 → 영문 image-generation prompt 변환 로그.
-- 목적: Phase 2(형태소 분석 + 도메인 사전) 진행 시 학습 데이터로 사용.
--   - 사용자가 실제로 어떤 한국어 표현을 쓰는지 빈도 분석
--   - 한→영 매핑 사전 1차 구축 베이스
-- 보존성: 분석용이므로 사용자/프로젝트가 삭제돼도 로그는 살려둔다 (FK SET NULL).
CREATE TABLE `prompt_translation_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NULL,
  `project_id` bigint NULL,
  `ko_prompt` text NOT NULL,
  `en_prompt` text NULL,
  `project_subject` varchar(255) NULL,
  `project_technique` varchar(255) NULL,
  `project_mood` varchar(255) NULL,
  `status` varchar(20) NOT NULL,
  `error_message` varchar(1000) NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_ptl_user_created` (`user_id`, `created_at`),
  KEY `idx_ptl_created` (`created_at`),
  CONSTRAINT `fk_ptl_user`
    FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_ptl_project`
    FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
