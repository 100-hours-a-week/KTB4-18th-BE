# 메인 지도 API 테스트 리포트 — 명세 정렬 전 기준선

> 이 문서는 `/api/v1/map-zones` 구현 시점의 불일치 기준선이다. 현재 명세 정렬 결과와 원문은 [`../map-dots/API_TEST_REPORT.md`](../map-dots/API_TEST_REPORT.md)를 확인한다.

## 결론

- 구현 API 기능 검증: **통과**. `GET /api/v1/map-zones`는 200 응답으로 1,050개 구역을 반환하고, 같은 ETag를 보낸 재검증 요청에는 304와 빈 본문을 반환했다.
- Google Sheets 계약 검증: **실패**. 설계서의 지도 API는 `GET /api/v1/map-dots`이며, 현재 구현 경로·응답 컬럼·오류 응답과 일치하지 않는다.
- 따라서 지도 화면 렌더링용 구현은 동작하지만, 스프레드시트 API 명세를 기준으로 한 계약 승인 상태는 실패다.

## 기준과 실행 환경

- 서버: `http://127.0.0.1:8080`
- 구현 경로: `GET /api/v1/map-zones`
- 스프레드시트: `REST_API_KTB4_Team_Project_18` > `REST API 설계서` 행 72
- 공통 규칙: `공통 규칙` 행 4, 8, 28
- Postman Desktop 12.28.6에서 세 요청을 실제 전송했다. Postman 응답 패널에서 001은 `200 OK`·`63 ms`·`271.37 KB`, 002는 `304 Not Modified`·`21 ms`, 003은 `403 Forbidden`·`30 ms`를 확인했다.
- Postman History는 응답을 로컬 파일로 내보내지 않으므로, 리포지터리에 재현 가능한 원문 증빙을 남기기 위해 같은 메서드·URL·헤더로 localhost를 다시 호출해 아래 파일을 캡처했다. 지도 카탈로그는 정적 배포 리소스이므로 001 본문은 Postman에서 확인한 본문과 같은 데이터다.

## 원문 증빙 파일

아래 파일은 테스트에 사용한 요청 데이터와 서버가 반환한 HTTP 원문이다. 304와 403은 본문이 없으므로 각각 0바이트 파일을 보관했다. `003` 헤더의 `JSESSIONID` 값만 보안상 `<redacted>`로 마스킹했다.

| 순번 | 요청 원문 | 응답 헤더 원문 | 응답 본문 원문 |
| --- | --- | --- | --- |
| 001 | `map-zones.postman_collection.json`의 `001` | `001-map-zones-200.response.headers.txt` | `001-map-zones-200.response.json` (277,528 bytes) |
| 002 | `map-zones.postman_collection.json`의 `002` | `002-map-zones-304.response.headers.txt` | `002-map-zones-304.response.body` (0 bytes) |
| 003 | `map-zones.postman_collection.json`의 `003` | `003-sheet-map-dots.response.headers.txt` | `003-sheet-map-dots.response.body` (0 bytes) |

`map-zones.postman_collection.json`은 Postman Import에 사용할 수 있는 Collection v2.1 원문이다.

## 데이터 원문 기반 검증

### 001 — 구현 지도 구역 조회

요청: `GET /api/v1/map-zones`

응답 원문에서 확인한 값:

```json
{
  "message": "map zones retrieved",
  "version": "2026-09-07",
  "zoneCount": 1050,
  "firstZone": {
    "code": "KR-COAST-5X5-0001",
    "gridRow": 36,
    "gridColumn": 39
  }
}
```

검증식은 다음을 모두 만족했다.

```text
message == "map zones retrieved"
data.version == "2026-09-07"
length(data.zones) == 1050
모든 zone의 키 == bounds, code, displayDots, gridColumn, gridRow
모든 zone의 bounds 키 == nw, se
모든 zone의 displayDots 길이 == 1
모든 dot의 relativeX == 0.5 && relativeY == 0.5
```

결과: **통과**. 응답 헤더에는 `ETag: "2026-09-07"`도 포함됐다.

### 002 — ETag 재검증

요청 헤더: `If-None-Match: "2026-09-07"`

결과: **통과**. 상태는 `304`, ETag는 `"2026-09-07"`, 응답 본문은 0바이트다.

### 003 — 스프레드시트 지도 API 경로

요청: `GET /api/v1/map-dots`

결과: **실패**. 실제 상태는 `403`, 응답 본문은 0바이트다.

## 스프레드시트 계약 대조

| 항목 | 설계서 | 실제 원문/구현 | 판정 |
| --- | --- | --- | --- |
| 경로 | `GET /api/v1/map-dots` | `GET /api/v1/map-zones` | 불일치 |
| 인증 | 지도 전체 도트 조회는 불필요 | `/map-zones`는 공개이지만 `/map-dots`는 403 | 불일치 |
| 200 data 최상위 | `items` | `version`, `zones` | 불일치 |
| 도트 식별자 | `map_dot_id`, `code` | `code`만 제공 | 불일치 |
| 대표 음악 컬럼 | `album_cover_url`, `latest_recorded_at` | 제공하지 않음 | 불일치 |
| 지도 좌표/격자 컬럼 | 설계서 행 72에는 없음 | `gridRow`, `gridColumn`, `bounds`, `displayDots` | 추가·명명 불일치 |
| JSON 명명 | snake_case | `gridRow`, `gridColumn`, `displayDots`, `relativeX`, `relativeY`는 camelCase | 불일치 |
| 공통 성공 응답 | `{ "message": string, "data": T | null }` | 같은 최상위 구조 | 일치 |
| 공통 보호 API 오류 | `401` + `{ "message": "unauthorized", "data": null }` | 설계서상 공개여야 하는 `/map-dots`가 `403` + 빈 본문 | 불일치 |

## 오류 코드 확인

- 구현 지도 API의 문서상 카탈로그 로드·검증 실패는 `500`과 `{ "message": "map zones are unavailable", "data": null }`이다.
- 이번 실행에서는 카탈로그가 정상 로드되어 500을 유발하지 않았다. 배포 리소스를 의도적으로 손상시키는 방식은 테스트 DB·작업 트리에 영향을 줄 수 있어 수행하지 않았다.
- 실제로 확인한 명세 경로 오류는 003의 `403`이며, Google Sheets의 공개 지도 조회 계약 및 공통 `401 unauthorized` 형식 어느 쪽과도 일치하지 않는다.

## 조치 권고

계약을 통과시키려면 팀 합의 후 한 가지를 선택해야 한다.

1. 구현을 스프레드시트에 맞춰 `/api/v1/map-dots` 및 snake_case `items` 응답으로 변경한다.
2. 스프레드시트의 지도 조회 계약을 현재 `/api/v1/map-zones`와 구역 기반 응답으로 변경한다.

그 전까지 프론트엔드는 현재 구현 API를 사용해 렌더링할 수 있으나, 문서 기반 통합 테스트와 다른 클라이언트는 계약 불일치를 겪는다.
