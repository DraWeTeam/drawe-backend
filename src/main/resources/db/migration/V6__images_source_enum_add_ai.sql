-- V1 baseline 의 images.source ENUM 이 ('UNSPLASH') 하나만 허용.
-- V5 에서 AI 메타데이터 컬럼들 (prompt, created_by_user_id, indexed_at) 은
-- 추가했으나 source ENUM 확장을 누락.
--
-- 결과: ImageGenerationService 가 ImageSource.AI 를 INSERT 시도 시
--       MySQL Error 1265 (Data truncated for column 'source') 발생.
-- 이 migration 이 'AI' 값을 ENUM 허용 목록에 추가하여 해결.
--
-- dev 환경은 이 PR 적용 전에 ALTER TABLE 로 즉시 hotfix 됨 — Flyway 의
-- MODIFY COLUMN 은 이미 같은 정의면 idempotent 라 충돌 없음.
ALTER TABLE `images`
  MODIFY COLUMN `source` ENUM('UNSPLASH', 'AI') NOT NULL;