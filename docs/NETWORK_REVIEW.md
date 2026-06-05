# 네트워크 대전 구현 리뷰

작성일: 2026-06-04

## 요약

현재 네트워크 대전 구현은 컴파일과 기존 테스트는 통과하지만, 실제 네트워크 플레이 안정성 기준으로는 보완이 필요하다.

우선순위가 높은 문제는 다음 네 가지다.

1. 클라이언트 미러 상태를 JavaFX UI 스레드 밖에서 변경한다.
2. 상대 연결이 끊기면 호스트 게임 루프가 영구 대기할 수 있다.
3. 네트워크 응답에 phase/request 식별자가 없어 stale 메시지에 취약하다.
4. 보유 한도 무시 같은 일부 플레이어 상태가 클라이언트 미러에 반영되지 않는다.

## 확인 결과

`./gradlew.bat test`는 통과했다.

다만 현재 테스트는 네트워크 연결, 연결 해제, 클라이언트 미러 동기화, 원격 입력 순서 등을 검증하지 않는다. 따라서 빌드 통과는 "컴파일 가능" 정도의 신호로 봐야 한다.

## 주요 문제

### 1. 클라이언트 미러 상태를 네트워크 스레드에서 직접 변경함

위치:

- `src/main/java/com/oop/payday/net/GameClient.java`
- `src/main/java/com/oop/payday/net/ClientMirror.java`

`GameClient.startReaderLoop()`는 reader thread에서 `mirror.applyState(env.state())`를 먼저 실행한 뒤, 이벤트 dispatch만 `Platform.runLater()`로 넘긴다.

`ClientMirror.applyState()`는 `Team`, `Player`, 카드 목록, 도우미 목록 등 UI가 읽는 모델 객체를 직접 변경한다. JavaFX UI 스레드가 같은 객체를 읽는 동안 네트워크 스레드가 상태를 바꾸면 이벤트 순서가 꼬이거나 화면이 순간적으로 잘못된 상태를 볼 수 있다.

예상 증상:

- 이벤트 애니메이션보다 상태가 먼저 바뀌어 연출과 필드 상태가 맞지 않음
- UI 갱신 중 목록이 바뀌어 간헐적인 예외나 이상 렌더링 발생
- 재현이 어려운 타이밍성 버그 발생

권장 수정:

- `applyState()`와 `dispatchEvent()`를 하나의 `Platform.runLater()` 안에서 순차 실행한다.
- 가능하면 이벤트 처리 전후 상태 적용 순서를 명확히 정한다.

### 2. 연결 해제 시 호스트 게임 루프가 영구 대기할 수 있음

위치:

- `src/main/java/com/oop/payday/net/GameServer.java`
- `src/main/java/com/oop/payday/player/NetworkPlayer.java`
- `src/main/java/com/oop/payday/game/Game.java`

`NetworkPlayer`는 원격 입력을 `SynchronousQueue.take()`로 기다린다. 분할, 선택, 도우미 선택 중 상대가 연결을 끊으면 `GameServer`의 reader loop는 종료되지만, 게임 루프는 여전히 `take()`에서 대기할 수 있다.

환금 단계에서도 `Game.cashInPhase()`는 `cashInbox.take()`로 제출을 기다린다. 이때 상대가 연결을 끊으면 게임 루프가 풀리지 않을 가능성이 있다.

예상 증상:

- 호스트 화면에는 연결 끊김 안내가 보이지만 게임 스레드는 내부에서 멈춤
- 다음 게임 시작이나 화면 전환 후에도 이전 daemon thread가 남음
- 네트워크 자원 정리가 불완전해짐

권장 수정:

- 연결 해제 시 `NetworkPlayer`에 cancel/abort 신호를 넣어 대기 중인 `take()`를 풀어준다.
- 환금 중에는 `cashSink.pass()` 또는 별도의 abort submission을 넣어 `cashInbox.take()`를 해제한다.
- 게임 루프에 `NetworkDisconnectedException` 같은 종료 경로를 두고 정상적으로 판을 중단한다.

### 3. 요청-응답 상관관계가 없어 stale 메시지에 취약함

위치:

- `src/main/java/com/oop/payday/net/NetMessage.java`
- `src/main/java/com/oop/payday/net/GameServer.java`
- `src/main/java/com/oop/payday/controller/NetworkInputGateway.java`

현재 클라이언트가 보내는 메시지는 `SplitDecision`, `ChoiceDecision`, `HelpersDecision`, `CashAction`, `CashPass` 정도만 담고 있다. 어떤 phase의 어떤 요청에 대한 응답인지 식별자가 없다.

서버는 메시지를 받으면 현재 게임 상태와 맞는지 검증하지 않고 바로 `NetworkPlayer`나 `CashSink`에 전달한다.

예상 증상:

- 늦게 도착한 이전 phase 응답이 다음 phase에 섞임
- 잘못된 타이밍의 `ChoiceDecision`이 `SynchronousQueue.put()`에서 reader thread를 막음
- 중복 클릭이나 재전송 상황에서 예측하기 어려운 입력 처리 발생

권장 수정:

- 서버가 요청을 보낼 때 `requestId`, `phase`, `round` 또는 `revision`을 같이 보낸다.
- 클라이언트 응답에도 같은 식별자를 포함한다.
- 서버는 현재 대기 중인 요청과 일치하는 응답만 처리하고, 오래된 응답은 버린다.
- 가능하면 응답 타입별로 현재 허용 상태를 명시적으로 검증한다.

### 4. 보유 한도 무시 상태가 클라이언트에 반영되지 않음

위치:

- `src/main/java/com/oop/payday/game/Game.java`
- `src/main/java/com/oop/payday/controller/GameBoardController.java`
- `src/main/java/com/oop/payday/net/ClientMirror.java`
- `src/main/java/com/oop/payday/net/PlayerStateDto.java`

도우미 효과로 `player.suspendHoldLimit()`가 적용되면 서버의 실제 `Player`에는 보유 한도 무시 상태가 저장된다. 하지만 `PlayerStateDto`에는 이 값이 없다.

클라이언트 UI는 환금 턴 종료 시 `player.isHoldLimitSuspended()`를 보고 종료 가능 여부를 판단한다. 미러 플레이어에는 이 값이 반영되지 않기 때문에 서버에서는 가능한 행동을 클라이언트 UI가 막을 수 있다.

예상 증상:

- 보유 한도 무시 도우미를 사용했는데도 클라이언트에서 턴 종료 버튼이 막힘
- 보유 한도 표시가 서버 상태와 다르게 보임

권장 수정:

- `PlayerStateDto`에 `holdLimitSuspended`를 추가하고 `ClientMirror`에 반영한다.
- 또는 환금 패널에서는 `Player`의 내부 상태 대신 `CashInContext.holdLimit()` 기준으로 판단한다. 현재 `snapshotFor()`는 보유 한도 무시 상태일 때 `Integer.MAX_VALUE`를 내려보내므로 이 값을 활용할 수 있다.

## 기타 리스크

### `ObjectInputStream` 사용

현재 네트워크 메시지 송수신은 Java 객체 직렬화 기반이다. LAN 실습/프로토타입 수준에서는 간단하다는 장점이 있지만, 신뢰할 수 없는 네트워크 입력을 받을 가능성이 있으면 보안상 위험하다.

장기적으로는 JSON, CBOR, MessagePack, protobuf 같은 명시적 wire format을 쓰는 편이 낫다.

### 리소스 정리

접속 실패나 취소 시 `GameClient`, socket, stream을 닫지 않는 경로가 있다. 큰 문제로 바로 터지지는 않더라도 반복 접속 테스트에서는 누적될 수 있다.

## 권장 작업 순서

1. `GameClient`에서 `ClientMirror.applyState()`를 JavaFX Application Thread에서 실행하도록 변경한다.
2. 연결 해제 시 `NetworkPlayer`와 환금 대기를 해제하는 abort 경로를 추가한다.
3. 네트워크 요청/응답에 `requestId` 또는 `phaseRevision`을 추가하고 서버 검증을 넣는다.
4. 보유 한도 무시 상태를 DTO로 전달하거나 환금 UI 판단을 `CashInContext.holdLimit()` 기준으로 바꾼다.
5. 네트워크 통합 테스트를 추가한다.
