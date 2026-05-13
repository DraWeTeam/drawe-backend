-- search_logs.project_id FK를 ON DELETE SET NULL로 변경.
-- 프로젝트 삭제 시 search_logs도 같이 지우면 분석/감사 이력이 손실되므로
-- project_id만 NULL로 끊고 로그 자체는 보존.
-- 관련: 로컬에서 프로젝트 삭제 시 FK constraint violation 발생하던 버그.

-- V1 baseline에서 자동 생성된 FK 이름이 환경마다 다를 수 있어
-- 하드코드하지 않고 information_schema에서 동적으로 찾아 DROP.
SET @fk_name = (
  SELECT CONSTRAINT_NAME
  FROM information_schema.KEY_COLUMN_USAGE
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'search_logs'
    AND COLUMN_NAME = 'project_id'
    AND REFERENCED_TABLE_NAME = 'projects'
  LIMIT 1
);

-- FK가 존재하면 DROP, 없으면 skip (예: 이전에 수동으로 DROP된 경우)
SET @sql = IF(@fk_name IS NOT NULL,
              CONCAT('ALTER TABLE search_logs DROP FOREIGN KEY `', @fk_name, '`'),
              'DO 0');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- project_id는 V1에서 이미 nullable이라 MODIFY 불필요.

ALTER TABLE search_logs
  ADD CONSTRAINT `fk_search_logs_project`
  FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`)
  ON DELETE SET NULL;