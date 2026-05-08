-- ╔══════════════════════════════════════════════════════════════════════╗
-- ║  V1__baseline.sql — Flyway baseline migration (TEMPLATE)             ║
-- ╠══════════════════════════════════════════════════════════════════════╣
-- ║  📌 이 파일은 템플릿입니다. 아래 절차대로 실제 스키마로 채우세요.    ║
-- ╚══════════════════════════════════════════════════════════════════════╝
--
-- 사용법:
--
-- 1) dev RDS 에서 schema-only dump:
--      mysqldump -h <DEV_RDS_ENDPOINT> -u drawe_admin -p \
--          --no-data --routines --triggers \
--          --skip-add-drop-table --skip-comments \
--          drawe_db > /tmp/baseline.sql
--
-- 2) /tmp/baseline.sql 을 열어서 CREATE TABLE 문들을 아래 PLACEHOLDER 자리에 붙여넣기.
--    단 다음은 제거:
--      - CREATE DATABASE / USE / SET 문
--      - DEFINER=`xxx`@`%` 절
--      - 마지막의 LOCK TABLES / UNLOCK TABLES (데이터 dump 가 아니므로 없음)
--
-- 3) 외래키 의존성에 맞게 순서 정렬:
--      - users 같은 참조되는 테이블 먼저
--      - projects, images 같은 참조하는 테이블 나중
--    어차피 mysqldump 가 알아서 정렬해주지만, 수동 편집 후 깨질 수 있어 한 번 더 확인.
--
-- 4) Flyway 의 baseline-on-migrate=true 설정 때문에 이 V1 은 "이미 적용된 것"
--    으로 간주됩니다. 실제 DB 에 SQL 이 다시 실행되지는 않아요.
--    V2__add_xxx.sql 부터 실제 적용됩니다.
--
-- 5) 새로운 마이그레이션은 다음 규칙으로 추가:
--      V2__add_user_email_verified.sql
--      V3__create_audit_log.sql
--      ...
--    숫자는 순차, double underscore 뒤에 short snake_case 설명.
--
-- ─────────────────────────────────────────────────────────────────────────

-- ╔══════════════════════════════════════════════════════════════════════╗
-- ║  ⬇️ 여기서부터 실제 스키마로 교체 ⬇️                                  ║
-- ╚══════════════════════════════════════════════════════════════════════╝

-- ───── PLACEHOLDER START ─────

SET FOREIGN_KEY_CHECKS = 0;

CREATE TABLE `chat_sessions` (
  `id` varchar(100) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `last_active` datetime(6) DEFAULT NULL,
  `project_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FKped6akl79n625egwltmyeowf6` (`project_id`),
  KEY `FK82ky97glaomlmhjqae1d0esmy` (`user_id`),
  CONSTRAINT `FK82ky97glaomlmhjqae1d0esmy` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`),
  CONSTRAINT `FKped6akl79n625egwltmyeowf6` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `image_drawe_tags` (
  `image_id` bigint NOT NULL,
  `free_tags` json DEFAULT NULL,
  `mood` varchar(30) DEFAULT NULL,
  `subject` varchar(30) DEFAULT NULL,
  `tagged_at` datetime(6) DEFAULT NULL,
  `tagged_by` varchar(20) DEFAULT NULL,
  `technique` varchar(30) DEFAULT NULL,
  `utility` json DEFAULT NULL,
  PRIMARY KEY (`image_id`),
  KEY `idx_img_tag_tech` (`technique`),
  KEY `idx_img_tag_sbj` (`subject`),
  KEY `idx_img_tag_mood` (`mood`),
  CONSTRAINT `FKle2i5v2irb4ia4uh4kbiyj4wf` FOREIGN KEY (`image_id`) REFERENCES `images` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `image_feedback` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `feedback` enum('LIKE','DISLIKE') NOT NULL,
  `image_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKlncyocvhohx85jdjf5wvhp778` (`user_id`,`image_id`),
  KEY `FKf769uomx25affqvaidlfn3cre` (`image_id`),
  CONSTRAINT `FKf769uomx25affqvaidlfn3cre` FOREIGN KEY (`image_id`) REFERENCES `images` (`id`),
  CONSTRAINT `FKg0s45ok4grfyix9nuj1omuciq` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `images` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `embedding_id` varchar(100) DEFAULT NULL,
  `is_onboarding` bit(1) NOT NULL DEFAULT b'0',
  `is_tagged` bit(1) NOT NULL DEFAULT b'0',
  `photographer_name` varchar(200) DEFAULT NULL,
  `photographer_username` varchar(100) DEFAULT NULL,
  `raw_tags` json DEFAULT NULL,
  `source` enum('UNSPLASH') NOT NULL,
  `source_id` varchar(100) DEFAULT NULL,
  `url` varchar(500) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_img_src_srcId` (`source`,`source_id`),
  KEY `idx_img_embedding` (`embedding_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `llm_messages` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `content` text NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `embedding_id` varchar(100) DEFAULT NULL,
  `error_message` text,
  `has_image` bit(1) NOT NULL DEFAULT b'0',
  `image_url` varchar(500) DEFAULT NULL,
  `latency_ms` int DEFAULT NULL,
  `model` varchar(100) DEFAULT NULL,
  `provider` varchar(20) DEFAULT NULL,
  `reference_ids` json DEFAULT NULL,
  `role` enum('SYSTEM','USER','ASSISTANT') NOT NULL,
  `status` enum('SUCCESS','FAILED') DEFAULT NULL,
  `chat_session_id` varchar(100) NOT NULL,
  `references_json` json DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_llm_session_created` (`chat_session_id`,`created_at`),
  CONSTRAINT `FKgaprv5uq3dhkg3qwtle818tkf` FOREIGN KEY (`chat_session_id`) REFERENCES `chat_sessions` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `project_references` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `added_at` datetime(6) DEFAULT NULL,
  `image_id` bigint NOT NULL,
  `project_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UKiq65j1sr2ffb6wlo7kf2n2iyg` (`project_id`,`image_id`),
  KEY `FKh6ychi23ufjdc0rnlphsqdbq` (`image_id`),
  CONSTRAINT `FKh6ychi23ufjdc0rnlphsqdbq` FOREIGN KEY (`image_id`) REFERENCES `images` (`id`),
  CONSTRAINT `FKjht9uh9261a943dw6wlkilqv1` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `projects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `description` tinytext,
  `detail_answers` json DEFAULT NULL,
  `drawing_url` varchar(500) DEFAULT NULL,
  `mood` varchar(30) DEFAULT NULL,
  `name` varchar(100) NOT NULL,
  `status` enum('IN_PROGRESS','COMPLETED') NOT NULL,
  `subject` varchar(100) DEFAULT NULL,
  `suggestions_shown` bit(1) NOT NULL DEFAULT b'0',
  `technique` varchar(30) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_proj_user_status` (`user_id`,`status`),
  CONSTRAINT `FKhswfwa3ga88vxv1pmboss6jhm` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `refresh_tokens` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `expiry_at` datetime(6) NOT NULL,
  `token` varchar(1000) NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `FK1lih5y2npsf8u5o3vhdb9y0os` (`user_id`),
  CONSTRAINT `FK1lih5y2npsf8u5o3vhdb9y0os` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `search_logs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `avg_score` double DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `extracted_keywords` varchar(500) DEFAULT NULL,
  `original_message` varchar(1000) DEFAULT NULL,
  `result_count` int DEFAULT NULL,
  `source` varchar(30) DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_search_log_user` (`user_id`),
  KEY `idx_search_log_created` (`created_at`),
  KEY `FK2negmiiimwqqukir2wv8e97uy` (`project_id`),
  CONSTRAINT `FK2negmiiimwqqukir2wv8e97uy` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`),
  CONSTRAINT `FKaxl330wos94o6v1a13oyvgqr0` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `user_pref_tags` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `axis` enum('AXIS_TECHNIQUE','AXIS_SUBJECT','AXIS_MOOD','AXIS_UTILITY') NOT NULL,
  `value` varchar(30) NOT NULL,
  `weight` int NOT NULL,
  `user_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_axis_value` (`user_id`,`axis`,`value`),
  KEY `idx_user_pref_tag_weight` (`user_id`,`weight`),
  CONSTRAINT `FK8kanncylgeivqc1eyxm2w457i` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE `users` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(255) NOT NULL,
  `nickname` varchar(100) NOT NULL,
  `password` varchar(255) DEFAULT NULL,
  `picture` varchar(500) DEFAULT NULL,
  `plan` enum('FREE','PAID') NOT NULL,
  `provider` varchar(20) DEFAULT NULL,
  `provider_id` varchar(100) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_6dotkott2kjsp8vw4d0m25fb7` (`email`),
  KEY `idx_user_prov_pid` (`provider`,`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET FOREIGN_KEY_CHECKS = 1;

-- ───── PLACEHOLDER END ─────

-- ╔══════════════════════════════════════════════════════════════════════╗
-- ║  ✅ 작성 후 체크리스트                                                ║
-- ╠══════════════════════════════════════════════════════════════════════╣
-- ║  □ 모든 entity 의 @Table(name=) 과 일치하는 CREATE TABLE 이 있는지   ║
-- ║  □ @Column 의 nullable/length/unique 가 SQL 의 컬럼 정의와 일치     ║
-- ║  □ @JoinColumn 의 FK 가 SQL 의 FOREIGN KEY 와 일치                  ║
-- ║  □ @Index 또는 @UniqueConstraint 가 SQL 에 반영                     ║
-- ║  □ ENGINE=InnoDB / charset utf8mb4 / collation utf8mb4_0900_ai_ci   ║
-- ╚══════════════════════════════════════════════════════════════════════╝
