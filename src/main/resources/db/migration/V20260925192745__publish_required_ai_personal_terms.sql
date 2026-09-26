DROP PROCEDURE IF EXISTS assert_terms_20260925192745;

DELIMITER //
CREATE PROCEDURE assert_terms_20260925192745()
BEGIN
    IF EXISTS (
        SELECT 1 FROM terms
        WHERE type = 'AIPERSONAL'
            AND (
                version NOT REGEXP '^v[0-9]+[.][0-9]+$'
                OR CAST(SUBSTRING_INDEX(SUBSTRING(version, 2), '.', 1) AS UNSIGNED) > 0
                OR CAST(SUBSTRING_INDEX(version, '.', -1) AS UNSIGNED) >= 3
            )
    ) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AIPERSONAL revision already exists';
    END IF;
END //
DELIMITER ;

CALL assert_terms_20260925192745();
DROP PROCEDURE assert_terms_20260925192745;

INSERT INTO terms (type, version, title, content, is_required, effective_at, created_at)
VALUES (
    'AIPERSONAL',
    'v0.3',
    'AI 맞춤 음악 추천 정보 이용 동의',
    '공개 범위 내 음악 기록·선호·추천 반응을 맞춤 추천에 이용합니다. 가입하려면 이 약관에 동의해야 합니다.',
    TRUE,
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
);
