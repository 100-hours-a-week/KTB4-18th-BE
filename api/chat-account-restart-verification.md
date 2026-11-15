# B10 변경된 참여 모델의 재시작 복구 검증

## 검증 대상

기존 chatProcessTest의 실제 Spring Boot child JVM과 MySQL 9.7.0 하네스를 확장했다.
하나의 계정으로 여러 JWT sid를 발급하고 실제 위치 판정·입장 REST와 STOMP를 호출한다.
동일 sid의 여러 탭, 동일 방의 다른 sid, 다른 지역의 sid를 함께 구성한다.
외부 역지오코딩은 테스트 응답으로 지역을 제어하며 실제 GPS 이동·인증 로그인 흐름과 구분한다.

## 시나리오

### 여러 세션·탭·지역 복구

- 원래 sid의 종료된 탭 1개와 열린 탭 2개, 같은 방의 다른 sid 1개, 다른 지역 sid 1개를 만든다.
- 실제 송수신과 조회로 각 활성 view의 checkpoint ID를 저장한다.
- 실제 child PID를 강제 종료하고 종료 확인 뒤 동일 DB에 미확인 메시지 fixture를 넣는다.
- 다른 PID로 재시작하고 각 원래 구간이 RESTART와 해당 checkpoint ID로 닫혔는지 확인한다.
- 끝난 탭의 HTTP403/CONNECT4103을 확인한다. 남은 탭 2개와 다른 sid·지역은 독립 복구한다.
- account history는 방별로 분리하고 미확인 fixture는 history와 본인 client-id 조회에서 제외한다.
- 정원을 1명으로 설정해 같은 계정의 여러 탭/sid 복구를 허용하고 다른 계정 입장은 409로 거절한다.
- 실제 인원은 각 방 1명이다. 복구된 한 탭을 종료해도 같은 sid의 나머지 탭은 전송 가능하다.
- 해당 sid의 마지막 탭까지 종료해도 다른 sid와 다른 지역의 실시간 전송은 유지된다.

### 종료된 참여의 이력과 현재 권한

- REST가 발급한 view를 실제 구독에 사용하고 마지막 탭 DELETE로 membership 종료를 확인한다.
- 같은 계정의 새 sid로 새로운 참여·여러 view를 만들고 history cursor를 발급받는다.
- JVM 강제 종료 후 일부 view를 삭제하고 일부 view의 갱신 시각을 만료 fixture로 조정한다.
- 서버 중단 중 메시지 fixture를 넣고 동일 DB로 다른 PID를 시작한다.
- 종료된 membership은 HTTP403/STOMP ERROR CHAT_LEFT이며 되살아나지 않는다.
- 삭제/만료된 view는 HTTP403/CONNECT4103이다. 정상 view만 재연결한다.
- 과거 membership의 허용 메시지는 account history에 남고 현재 탭 recovery는 새 참여 구간만 제공한다.
- 재시작 전 history cursor는 원래 snapshot/권한 범위만 제공한다.
- 재시작 후 또 다른 sid의 새 view도 계정 과거 대화를 조회하지만 자신의 recovery는 빈 구간으로 시작한다.
- 다른 sid의 현재 참여를 빌리는 요청은 403, 다른 현재 문맥에 기존 cursor를 재사용하는 요청은 400인지 확인한다.

## 실행

```sh
./gradlew chatProcessTest --tests '*ChatRecoveryProcessIntegrationTest.restart*' --no-daemon
./gradlew test --tests 'com.muse.meomuneum.chat.*' chatProcessTest bootJar --no-daemon
```

일반 test는 프로세스 테스트를 제외하므로 chatProcessTest도 별도로 실행해야 한다.
Docker와 JDK25가 필요하다. 제품 서버나 운영 DB에 접속하지 않는다.
실제 FE 연동을 함께 실행하려면 기존 CHAT_RECOVERY_FE_DIR을 지정할 수 있으나 B10의 필수 범위는 BE다.
선택 브라우저 유지 fixture의 CHAT_BROWSER_FE_DIR은 일반 검증에서 지정하지 않는다.

## 증빙과 범위

기존 build/chat-process-verification/run-*/의 server 로그/result.json과 JUnit XML을 사용한다.
result.json의 participation_restarts는 B10 시나리오별 old_pid/new_pid/same_database를 기록한다.
JWT·쿠키·위치 증명 원문은 검증 문서나 증빙 요약에 넣지 않는다.

미확인 메시지는 서버 종료 뒤 넣은 전용 DB fixture다. 실제 장애 직전 ACK·디스크 손실을 재현했다고 표시하지 않는다.
삭제/만료는 전용 DB 상태 조작이며 운영 관리 API·실제 24시간 대기 검증과 구분한다.
실제 물리 기기·GPS·모바일·로그인/refresh 쿠키·인증 세션 폐기·다중 서버는 미검증이다.
B7의 인증 담당자 새 이벤트 발행 의존성은 그대로 유지한다.
운영 코드·공개 API·DDL/Flyway·환경 변수·의존성·FE 변경은 없다.

## 결과

- 일반 채팅 테스트: 192개 중 188개 통과, 실서버 조건부 4개 제외, 실패 0개.
- 최종 프로세스 테스트: 8개 중 6개 통과, 선택 FE·브라우저 2개 제외, 실패 0개.
- 신규 재시작 시나리오 2개 모두 통과했다. 같은 sid의 열린 탭 2개 복구도 포함한다.
- bootJar·Checkstyle main·채팅 Java 범위 Spotless는 통과했다.
- Checkstyle test는 기존 인증 테스트 LocalMockLoginServiceTest.java:40의 121자 줄 1건으로 실패했다. 채팅 파일 위반은 없다.
- 저장소 전체 Spotless의 기존 인증·설정 5개 파일 위반은 이번에 수정하지 않았다. 채팅 범위 포맷 확인에는 임시 Gradle init을 사용했고 저장소 설정은 변경하지 않았다.
- 전체 test는 기존 외부 DB·인증 초기화 실패 기록을 고려해 재실행하지 않았다. 전체 검사 성공으로 표시하지 않는다.

최종 증빙은 build/b10-account-restart-verification/summary.json, test/, final-process/, final-process-result.json에 보존한다.
실제 서버 로그는 build/chat-process-verification/run-1792306658937/에 있다.
서버는 총 4회 시작했으며 B10 두 번의 PID 변경을 기록했다. 종료 후 해당 서버 PID가 남지 않았음을 확인했다.
테스트 코드와 검증 문서는 각각 별도 커밋으로 분리한다.
