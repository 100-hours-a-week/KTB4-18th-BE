# 메인페이지 지도 API

`GET /api/v1/map-dots`는 인증 없이 메인페이지의 읽기 전용 도트 목록을 제공한다. 구역 좌표는 데이터베이스 엔터티가 아니라 `src/main/resources/map-zones.json`의 배포 리소스이며, 이번 기능은 ERD·Flyway를 변경하지 않는다.

## 요청과 캐시

- 첫 요청: `GET /api/v1/map-dots`
- 재검증: 이전 응답의 `ETag`를 `If-None-Match` 헤더에 넣어 요청한다.
- 동일한 카탈로그 version이면 본문 없이 `304 Not Modified`를 반환한다.

## 200 응답

```json
{
  "message": "map dots retrieved",
  "data": {
    "items": [
      {
        "map_dot_id": 1,
        "code": "KR-COAST-5X5-0001",
        "album_cover_url": null,
        "latest_recorded_at": null
      }
    ]
  }
}
```

`map_dot_id`는 카탈로그의 고정 순서로 생성한 읽기 전용 식별자다. 현 ERD에는 `map_dots`·음악 기록 테이블이 없으므로 `album_cover_url`, `latest_recorded_at`은 명세 필드를 유지한 `null` 값으로 반환한다. 화면 좌표는 프론트엔드에 포함된 동일 코드의 1,050개 격자에서 결합한다. 좌표 판정이 필요한 후속 서버 기능은 카탈로그의 NW·SE 대각 꼭짓점만 사용해 `north >= latitude > south`, `west <= longitude < east` 규칙으로 구역을 찾는다.

## 실패 처리

카탈로그를 읽거나 검증할 수 없으면 서버는 `500`과 `{ "message": "map dots are unavailable", "data": null }`를 반환한다. 프론트엔드는 요청을 즉시 한 번 재시도하고, 두 번 실패하면 포함된 1,050개 fallback 격자를 화면에 표시한다.
