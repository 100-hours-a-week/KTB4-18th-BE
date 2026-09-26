ALTER TABLE terms
    MODIFY COLUMN version VARCHAR(10) NOT NULL,
    MODIFY COLUMN title VARCHAR(100) NOT NULL;

UPDATE terms
SET is_required = TRUE,
    content = REPLACE(content,
        '동의하지 않아도 일반 추천과 기본 기능을 이용할 수 있습니다.',
        '가입하려면 이 약관에 동의해야 합니다.')
WHERE id = 2 AND type = 'AIPERSONAL';

UPDATE terms
SET content = REPLACE(content,
    ' 가입 요청의 동의 값으로 받지 않고 열람 안내로 제공합니다.', '')
WHERE id = 5 AND type = 'PRIVACY';
