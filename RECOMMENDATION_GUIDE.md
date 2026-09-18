# 텍스트 음악 추천 개발 가이드

## 실행

백엔드는 기존 README대로 개발 DB와 `.env`를 준비하고 `dev` 프로필로 실행합니다.

```sh
set -a
. ./.env
set +a
./gradlew bootRun --args='--spring.profiles.active=dev'
```

프론트엔드 저장소에서 `npm run dev`로 실행하고 http://localhost:5173 에 접속합니다.
Vite가 `/api` 요청을 localhost:8080으로 전달하므로 로컬 CORS 설정은 필요 없습니다.
운영에서는 같은 도메인의 `/api`를 백엔드로 연결하세요. 별도 도메인을 사용한다면
VITE_API_BASE_URL과 정확한 Origin·credentials CORS 및 쿠키 설정을 팀의 인증 흐름에 맞춰 추가해야 합니다.

## 코드 읽는 순서

1. 프론트엔드 `src/App.tsx`: 입력 → 사용자 말풍선 → 서버 요청 → 추천 말풍선.
2. 프론트엔드 `src/api/recommendations.ts`: POST 요청 후 GET으로 결과 확인. 실제 AI 처리 시 PROCESSING 상태도 대기할 수 있습니다.
3. 백엔드 `RecommendationController`: 요청 형식 검증과 API 응답.
4. `RecommendationService`: 추천 제공자 호출 → 결과 저장 → 소유권 확인 후 조회.
5. `SampleRecommendationProvider`: iTunes에서 확보한 실제 곡 15개 중 키워드로 5곡 선정.
6. `RecommendationRepository`: JdbcTemplate으로 SQL 실행. 세션·음악·추천 순서를 하나의 트랜잭션으로 저장.

## API와 저장

POST /api/v1/recommendations

```json
{
  "input_type": "TEXT",
  "trigger_type": "CHATBOT",
  "conversation_key": "550e8400-e29b-41d4-a716-446655440000",
  "prompt": "비 오는 밤에 듣기 좋은 음악"
}
```

응답은 `{ message, data }`와 snake_case를 사용합니다. POST는 설계서의 202를 유지하며,
현재 샘플은 동기 처리하므로 data.status가 이미 COMPLETED입니다.
GET /api/v1/recommendations/{recommendation_id}로 저장된 결과를 조회합니다.
items는 rank_no와 music(곡명·아티스트·앨범 이미지·preview_url)을 가진 5개 항목입니다.
입력은 공백 불가·최대 1000자이며 TEXT·CHATBOT만 지원합니다. 날짜 응답은 UTC ISO-8601입니다.

DB에는 music, recommendation_sessions, recommendation_items만 생성합니다.
prompt 컬럼은 참조 ERD 호환용으로 두되 항상 NULL이고 저장 SQL에도 넣지 않습니다.
user_id·region_id는 사용자 승인에 따라 개발 단계에서 NULL을 허용합니다.
비로그인 결과의 소유권 확인을 위해 guest_session_id를 추가했습니다.
로그인·위치 기능이 완성되면 실제 값과 FK를 새 마이그레이션으로 추가하세요.
음악은 provider + external_music_id로 재사용하며 추천 결과와 순서는 매 요청마다 저장합니다.
V1 마이그레이션은 추천 개발 범위만 생성하므로 팀의 전체 초기 스키마와 통합이 필요합니다.
이미 동일 이름의 테이블이 있는 DB에는 이 초기 마이그레이션을 그대로 적용하지 마세요.

## 로그인·실제 API로 교체

- RecommendationProvider를 구현하는 실제 API 클래스를 만들고 샘플 @Component를 제거하거나 프로필로 구분합니다.
  각 음악을 TrackData로 변환하여 서로 다른 5곡을 반환하면 저장·화면 코드는 그대로 사용할 수 있습니다.
- CurrentUserResolver에서 검증된 Authentication principal의 users.id를 반환하도록 수정합니다.
  지금은 비로그인만 지원하며 로그인 객체가 들어오면 503을 반환하여 잘못된 사용자 ID로 저장하지 않습니다.
- RecommendationSecurityConfig는 dev의 allow-guests=true에서만 비로그인 요청 및 CSRF 예외를 허용합니다.
  기본값은 false입니다. 팀의 JWT 인증 필터와 SecurityFilterChain을 통합해야 운영 로그인 요청이 작동합니다.
- 실제 AI가 오래 걸리면 저장과 외부 호출을 분리하고 PROCESSING → COMPLETED/FAILED 작업 처리를 추가합니다.
  현재는 간단한 동기 샘플 구현입니다.
- conversation_key는 화면을 여는 동안 유지하지만 샘플은 현재 입력만 분석합니다.
  이전 입력 조건을 합치는 AI 대화 기능은 구현하지 않았고 입력은 새로고침 시 사라집니다.
- 추천 결과는 DB에 남지만 마이페이지·추천 이력 목록 화면은 이번 범위에 포함하지 않았습니다.
  게스트는 같은 서버 세션에서만 GET으로 조회할 수 있고, 로그인 시 과거 게스트 결과를 자동 귀속하지 않습니다.

## 미리 듣기

음원과 이미지는 iTunes Search API의 2026-09-17 샘플 URL을 사용합니다.
https://bendodson.com/projects/itunes-artwork-finder/ 는 같은 iTunes API를 사용하는 이미지 검색 도구이며
자동화 안내에 따라 해당 웹사이트를 크롤링하지 않고 Apple API를 직접 조회했습니다.
음원·이미지는 다운로드하거나 재배포하지 않고 제공 URL로 표시·재생합니다.
한 곡씩 재생하며 30초에 중지합니다. 새 추천 요청·다른 곡 재생·화면 종료 시 이전 재생을 중지합니다.
외부 URL은 만료되거나 제공이 중단될 수 있고, 재생 실패는 화면에 표시하며 이미지 실패는 기본 커버로 대체합니다.
샘플 음원은 곡과 일치하는 실제 미리 듣기이며 AI 분석 결과로 표시하지 않습니다.

## 검증

프론트엔드: `npm run lint`, `npm run build`.
백엔드: 개발·테스트 모두 MySQL만 사용합니다. 기존 README처럼 별도 `meomuneum_test` DB와
TEST_DB_PASSWORD 등 환경변수를 준비한 후 `./gradlew test`를 실행합니다.
추천 API 테스트는 test 프로필에서 요청·응답, 5곡 저장, 입력 미저장, 입력 검증,
다른 게스트의 접근 거절, 음악 중복 방지와 롤백을 검증합니다.
테스트 데이터 변경은 롤백하며 기존 데이터를 삭제하지 않습니다. Flyway 스키마 변경은 롤백되지 않습니다.
개발 DB나 운영 DB를 TEST_DB_URL에 지정하지 마세요.
