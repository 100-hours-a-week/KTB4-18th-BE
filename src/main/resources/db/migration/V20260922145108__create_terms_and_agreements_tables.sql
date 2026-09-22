CREATE TABLE terms (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    type ENUM('SERVICE', 'AIPERSONAL', 'PROFILE', 'LOCATION', 'PRIVACY', 'LOCATIONTERMS') NOT NULL,
    version VARCHAR(20) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    is_required BOOLEAN NOT NULL,
    effective_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL
);

CREATE TABLE terms_agreements (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    terms_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    deleted_at DATETIME(6) NULL,
    CONSTRAINT fk_terms_agreements_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_terms_agreements_terms FOREIGN KEY (terms_id) REFERENCES terms(id)
);

INSERT INTO terms (id, type, version, title, content, is_required, effective_at, created_at) VALUES
    (1, 'SERVICE', 'v0.2', '서비스 이용약관',
     '머문음 서비스의 이용 조건, 회원의 권리·의무 및 책임 사항을 정합니다. 동의하지 않으면 가입할 수 없습니다.',
     TRUE, '2026-09-20 00:00:00.000000', '2026-09-20 00:00:00.000000'),
    (2, 'AIPERSONAL', 'v0.2', 'AI 맞춤 음악 추천 정보 이용 동의',
     '공개 범위 내 음악 기록·선호·추천 반응을 맞춤 추천에 이용합니다. 동의하지 않아도 일반 추천과 기본 기능을 이용할 수 있습니다.',
     FALSE, '2026-09-20 00:00:00.000000', '2026-09-20 00:00:00.000000'),
    (3, 'PROFILE', 'v0.2', '출생연도·성별의 맞춤 추천 이용 동의',
     '가입 시 수집한 출생연도·성별과 음악 기록·선호를 맞춤 음악 추천에 이용합니다. 동의 철회 또는 회원 탈퇴 시까지 보유합니다.',
     FALSE, '2026-09-20 00:00:00.000000', '2026-09-20 00:00:00.000000'),
    (4, 'LOCATION', 'v0.2', '개인위치정보 수집·이용 동의',
     '기기에서 취득한 현재 위치와 수집 시각을 현재 위치 기반 장소 탐색 및 위치 기반 기록 기능에 이용합니다.',
     FALSE, '2026-09-20 00:00:00.000000', '2026-09-20 00:00:00.000000'),
    (5, 'PRIVACY', 'v0.2', '개인정보 처리방침',
     '계정·가입 정보의 처리 목적과 보유 기간, 이용자 권리를 안내합니다. 가입 요청의 동의 값으로 받지 않고 열람 안내로 제공합니다.',
     FALSE, '2026-09-20 00:00:00.000000', '2026-09-20 00:00:00.000000'),
    (6, 'LOCATIONTERMS', 'v0.2', '위치기반서비스 이용약관',
     '현재 위치 기반 장소 탐색 및 위치 기반 기록 서비스를 위한 약관입니다. 동의·철회, 일시 중지, 열람·고지 요구 권리를 안내합니다.',
     FALSE, '2026-09-20 00:00:00.000000', '2026-09-20 00:00:00.000000');
