# 메인 지도 도트 API 재검증 리포트

## 결론

- Google Sheets의 `REST API 설계서` 행 72와 비교한 `GET /api/v1/map-dots`의 경로, 공개 접근, 성공 응답 envelope 및 응답 컬럼은 **일치**한다.
- 원문 응답에는 `items`가 1,050개이며, 모든 항목에 `map_dot_id`, `code`, `album_cover_url`, `latest_recorded_at`가 있다.
- 현 ERD에 지도 도트·음악 기록 테이블이 없으므로 대표 음악 두 필드는 `null`이다. 이는 컬럼의 존재 여부는 충족하지만, 실제 대표 음악 값은 관련 ERD와 기록 저장 기능이 연결된 뒤에 채워야 한다.

## 기준 및 실행 환경

- 명세: `REST_API_KTB4_Team_Project_18` > `REST API 설계서` 행 72, `공통 규칙` 행 4·8
- 서버: `http://127.0.0.1:8081` (test 프로필 로컬 실행)
- 요청 원문: `map-dots.postman_collection.json` — Postman Collection v2.1 import 가능
- 응답 원문: 아래 표의 HTTP 헤더·본문 파일. 본문을 축약하거나 변환하지 않고 저장했다.

## 원문 증빙 파일

| 순번 | 요청 | 응답 헤더 원문 | 응답 본문 원문 |
| --- | --- | --- | --- |
| 001 | `GET /api/v1/map-dots` | `001-map-dots-200.response.headers.txt` | `001-map-dots-200.response.json` |
| 002 | `GET /api/v1/map-dots` + `If-None-Match: "2026-09-07"` | `002-map-dots-304.response.headers.txt` | `002-map-dots-304.response.body` (0 bytes) |

## 데이터 원문 기반 검증

### 001 — 전체 도트 지도 조회

검증식:

```text
HTTP status == 200
message == "map dots retrieved"
length(data.items) == 1050
first item == {
  map_dot_id: 1,
  code: "KR-COAST-5X5-0001",
  album_cover_url: null,
  latest_recorded_at: null
}
all item keys == album_cover_url, code, latest_recorded_at, map_dot_id
```

결과: **통과**.

### 002 — ETag 재검증

검증식:

```text
HTTP status == 304
ETag == "2026-09-07"
response body size == 0
```

결과: **통과**. ETag는 명세에 추가하지 않은 선택적 HTTP 캐시 헤더이며, 200 응답 JSON 구조에는 추가 필드를 넣지 않는다.

## Google Sheets 계약 대조

| 항목 | 명세 | 실제 원문 | 판정 |
| --- | --- | --- | --- |
| 경로·메서드 | `GET /api/v1/map-dots` | 동일 | 일치 |
| 인증 | 불필요 | 200 응답 | 일치 |
| 성공 envelope | `message`, `data` | 동일 | 일치 |
| data 최상위 | `items` | 동일 | 일치 |
| item 컬럼 | `map_dot_id`, `code`, `album_cover_url`, `latest_recorded_at` | 동일한 snake_case 키 | 일치 |
| 성공 메시지 | `map dots retrieved` | 동일 | 일치 |
| 404 오류 | 상세·음악기록 조회에만 `map dot not found` | 전체 조회 범위 밖이라 미호출 | 해당 없음 |

## 오류 코드 확인

행 72의 전체 도트 조회는 성공 응답만 정의한다. 따라서 이 리포트에서는 정상 200과 선택적 304만 실제 호출했다. 카탈로그를 읽지 못하는 서버 내부 실패는 구현상 `500`과 `{ "message": "map dots are unavailable", "data": null }`로 처리한다. 정적 카탈로그를 의도적으로 손상시키는 테스트는 작업 트리를 훼손하므로 수행하지 않았다.

`map dot not found` 404는 행 74·77의 상세·음악 기록 API용이며, 아직 이번 메인 지도 범위에는 포함하지 않았다.
