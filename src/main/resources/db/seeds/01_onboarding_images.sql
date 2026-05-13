-- 온보딩 후보 12개 마킹.
-- 큐레이션 결과라서 Flyway 마이그레이션이 아닌 시드로 관리.
-- 새 환경 셋업 시 reference 데이터(images, image_drawe_tags) import 후 실행.

-- 기존 온보딩 해제
UPDATE images SET is_onboarding = false WHERE is_onboarding = true;

-- 12개 온보딩 후보 설정
UPDATE images SET is_onboarding = true
WHERE id IN (27817, 27868, 21417, 26190, 5358, 4190, 11683, 12983, 12986, 15613, 25419, 26754);

-- 검증용 (수동 실행 시 결과 보고 확인)
SELECT i.id, t.subject, t.mood, i.url
FROM images i
JOIN image_drawe_tags t ON t.image_id = i.id
WHERE i.is_onboarding = true
ORDER BY i.id;