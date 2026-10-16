# 기기별 채팅 참여 DB 구조

B2는 인증 세션별 참여를 저장할 구조를 준비한다. 공개 API와 현재 입퇴장 동작은 그대로다.
시작 커밋은 `09f0599`이며 현재 브랜치는 `feature/chat-device-membership-schema`다.

## 변경 내용

신규 migration: `V20261016090922__add_auth_session_to_chat_memberships.sql`.
이름은 팀 스크립트가 생성한 KST 시각을 사용했다. 적용된 migration은 수정하지 않았다.

| 항목 | 의미 |
| --- | --- |
| `auth_session_id` | B1의 서명된 JWT sid. nullable ASCII UUID, 대소문자 구분. NULL은 원래 세션을 알 수 없는 기존 참여 |
| `active_auth_session_id` | 종료되지 않은 행의 sid. 유일 인덱스로 인증 세션당 활성 참여 1건 보장 |
| `active_user_id` | sid가 NULL이고 종료되지 않은 행에만 사용자 ID 유지. 기존 유일 인덱스로 구버전 저장 동작 보호 |
| `IDX_CHAT_ROOM_MEMBERS_AUTH_HISTORY` | sid·종료 여부로 참여 기록 조회 지원 |
| `CK_CHAT_ROOM_MEMBERS_AUTH_SESSION` | NULL 또는 소문자 UUID 형식 허용 |

기존 PK, 사용자·방 FK, 이력·방 조회 인덱스, 생성·종료 시각은 유지한다.
`chat_view_sessions.membership_id` 및 연결된 열람 구간도 유지한다.
세션은 Hazelcast에서 관리하므로 SQL 인증 세션 테이블에 대한 FK는 만들지 않는다.
과거 sid를 추정해 채우거나 참여·메시지·열람 기록을 삭제하지 않는다.

같은 사용자의 서로 다른 sid는 같은 방 또는 다른 방에 활성 행을 가질 수 있다.
같은 sid의 중복 활성 행은 방·사용자에 관계없이 거절하며, 종료 후에는 새 참여 행을 저장할 수 있다.
NULL sid와 실제 sid의 행이 함께 존재하는 전환 상태도 DB는 허용한다.
따라서 이 migration만으로 다중 기기 기능이 완성되지는 않는다.

## 적용 순서와 호환성

1. 대상 MySQL 버전·설정과 백업 복원 절차를 확인한다. 아래 FK 제한을 먼저 검토한다.
2. 사용자·방에 연결되지 않은 참여가 없는지 확인한다. 기존 활성 참여 중복, 참여·열람 행 수와 시각도 백업과 대조한다.
3. 유지보수 시간에 쓰기를 멈추고 Flyway로 적용한다. STORED 생성 컬럼 변경은 테이블 재구성과 잠금을 유발할 수 있으므로 무중단·INSTANT 적용을 가정하지 않는다.
4. Flyway validate, 적용 이력, 아래 데이터 검증 및 기존 입장·퇴장 smoke를 확인한 뒤 쓰기를 재개한다. 운영 적용은 이번 작업에서 실행하지 않았다.
5. B3 방별 계정 중복 제거 정원 계산과 B4 인증 세션별 참여 처리가 준비된 후에만 실제 sid 쓰기·다중 참여를 활성화한다.

사전·사후 확인 SQL:

```sql
SELECT COUNT(*) AS orphan_count
FROM chat_room_members m
LEFT JOIN users u ON u.id=m.user_id
LEFT JOIN chat_rooms r ON r.id=m.room_id
WHERE u.id IS NULL OR r.id IS NULL;

SELECT user_id, COUNT(*)
FROM chat_room_members WHERE deleted_at IS NULL
GROUP BY user_id HAVING COUNT(*) > 1;

SELECT COUNT(*) AS membership_count,
       SUM(deleted_at IS NULL) AS active_count,
       MIN(created_at) AS first_created_at, MAX(created_at) AS last_created_at
FROM chat_room_members;

SELECT COUNT(*) AS view_count,
       SUM(m.id IS NULL) AS missing_membership_count
FROM chat_view_sessions v LEFT JOIN chat_room_members m ON m.id=v.membership_id;

SHOW CREATE TABLE chat_room_members;
```

사용자별 활성 중복 검사는 B2 적용 전·sid 쓰기 활성화 전에 사용하는 점검이다.
후속 구현에서 여러 sid를 사용하는 시점에는 사용자 중복을 오류로 판단하지 않는다.
현재 JPA는 새 컬럼을 기록하지 않아 NULL로 저장되며 기존 유일성 제약이 적용된다.
Repository와 메모리 정원 관리에는 여전히 사용자 전역 단일 참여 전제가 있으므로, 임의 sid 백필이나 수동 다중 참여 생성을 하지 않는다.

애플리케이션만 되돌리는 경우 추가 컬럼은 남긴다. 이미 적용한 migration 수정·삭제, Flyway repair로 이력 제거를 하지 않는다.
후속 버전이 실제 sid를 기록한 뒤에는 예전 단일 참여 코드로 단순 롤백할 수 없다.
쓰기 중단·연결 종료·데이터 백업 후 참여 전환 및 별도 보정 migration을 먼저 설계해야 한다.

## 검증 결과와 한계

- 원본 `spotlessCheck`와 컴파일 작업을 제외한 `checkstyleMain/checkstyleTest` 통과. 두 로컬 migration 준비 작업 통과. 생성된 두 SQL 사본이 원본과 동일하다.
- 원본 `compileJava`는 기존 `AuthExceptionHandler`의 누락된 `AuthSessionController`·`LoginQueueController` 참조로 실패한다. 인증 코드는 수정하지 않았다.
- 격리 사본 `/private/tmp/meomuneum-b2-db-verify-szcf7ht1`에서 main Java 소스를 제외하고 migration 테스트 3개 클래스만 컴파일했다. 제품 SQL·검증 코드는 원본과 같다. 원본 전체 테스트·빌드 성공으로 취급하지 않는다.
- MySQL `9.7.0`·native FK 검사 ON에서 신규 8개 및 기존 기반/지역 seed 회귀 2개, 합계 10개 통과·제외 0개. Checkstyle test 통과.
- 기존 행·시각·열람 FK 보존, NULL sid 호환성, 서로 다른 sid의 같은/다른 방 참여, 중복 삽입·수정 거부, 종료 후 새 참여, NULL 전환·종료 행 복원 충돌, 형식·FK 거절, 동시 삽입 1건 성공을 확인했다.
- 기존 스키마 위에서 실제 Flyway baseline→신규 migration 1회→validate→재실행 0건을 검증했다. 테스트 baseline 설정은 운영 설정 변경 지침이 아니다.
- 전체 서비스 시작·실제 다중 기기 입퇴장·운영 데이터 규모의 DDL 잠금 시간은 이번 검증에 포함하지 않았다.

### 기본 FK 모드에서 발견한 기존 문제

최초 기본 설정 테스트에서 존재하지 않는 사용자에 대한 활성 참여 INSERT가 성공했다.
변경 전 스키마에서도 같은 잘못된 INSERT가 성공하며, FK 메타데이터와 `foreign_key_checks=1`은 유지되어 있었다.
`(user_id, deleted_at)`·`(room_id, deleted_at)`의 NULL suffix에서 검사 누락이 발생하는 현상이다.
단일 컬럼 인덱스를 추가하는 진단만으로는 해결되지 않았으며, 해당 임시 변경은 제품 migration에 넣지 않았다.
[MySQL 버그 #121438](https://bugs.mysql.com/bug.php?id=121438)에 같은 유형의 현상과 native FK 검사 사용 우회책이 보고되어 있다. 보고된 버전과 이 프로젝트의 태그는 구분하며, 위 결과는 로컬 `mysql:9.7.0` 이미지에서 관찰한 내용이다.

최종 테스트 컨테이너는 `--innodb-native-foreign-keys=ON`을 명시한다.
검증을 통과시키기 위해 FK를 삭제하거나 실패 경로를 제외하지 않았다.
운영 설정은 바꾸지 않았으며 기본 모드의 FK 문제 해결 여부를 검증했다고 주장하지 않는다.
운영 적용 전 실제 서버 설정·기존 고아 행을 확인하고 별도 DB 무결성 작업에서 native FK 설정 또는 인덱스 변경의 영향을 검토해야 한다.

진단 과정의 실패는 UUID 제약이 아니라 기본 모드 FK 거절 단정이었다. 추가 진단 사본의 일시적 unreachable-statement 컴파일 오류를 수정한 후 실제 DDL을 확인했다.
최종 제품 파일에는 진단 코드와 임시 인덱스가 남아 있지 않다.
