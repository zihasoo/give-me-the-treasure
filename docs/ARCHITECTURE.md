# 프로그램 구조

이 문서는 `give-me-the-treasure`가 어떻게 화면, 게임 규칙, 플레이어 입력, 봇, 네트워크를 분리해서 동작하는지 설명하는 문서입니다. 코드를 처음 읽는 사람도 전체 흐름을 잡을 수 있도록 파일 경로 중심으로 정리합니다.

## 큰 그림

프로젝트는 JavaFX 애플리케이션이지만, 게임 규칙 자체는 JavaFX에 묶여 있지 않습니다. 핵심은 다음 세 층입니다.

```text
화면 계층
  GameApp, FXML, GameBoardController, view/*, CSS

게임 규칙 계층
  Game, Team, GameConfig, Phase, model/*, decision/*

플레이어/입력 계층
  Player, HumanPlayer, BotPlayer, NetworkPlayer, InputGateway, bot/*, net/*
```

`Game`은 라운드와 규칙을 진행하는 상태 머신입니다. `GameBoardController`는 그 상태 변화를 받아 화면에 그립니다. `Player`는 "누가 결정을 내리는가"를 추상화합니다. 사람이든 봇이든 네트워크 너머의 사람이든, `Game` 입장에서는 같은 `Player`입니다.

## 진입점과 화면 전환

애플리케이션 진입점은 [Main.java](../src/main/java/com/oop/payday/Main.java)입니다. JavaFX 런타임 인식 문제를 피하기 위해 순수 런처인 `Main`이 [GameApp.java](../src/main/java/com/oop/payday/app/GameApp.java)를 실행합니다.

`GameApp`은 화면 전환을 담당합니다.

- `showScene("main_menu.fxml")`로 메인 메뉴를 띄웁니다.
- `showLobby(...)`와 `showClientLobby(...)`로 대기실을 띄웁니다.
- `showGameBoard(...)`로 오프라인 게임을 시작합니다.
- `showHostGame(...)`으로 호스트 권위 게임을 시작합니다.
- `showClientGame(...)`으로 클라이언트 미러 화면을 시작합니다.

FXML과 CSS는 `src/main/resources/com/oop/payday` 아래에 있습니다. JavaFX 컨트롤러는 `controller` 패키지에, 재사용 가능한 UI 빌더는 `view` 패키지에 있습니다.

## 게임 루프

게임 규칙의 중심은 [Game.java](../src/main/java/com/oop/payday/game/Game.java)입니다. `Game.play()`는 승자가 나올 때까지 다음 순서로 라운드를 진행합니다.

```text
setupHelpers
-> schemePhase
-> distributePhase
-> cashInPhase
-> endPhase
-> swapRoles
```

각 단계는 규칙서의 단계와 거의 1:1로 대응합니다.

- `schemePhase`는 분할 팀 리더에게 카드 5장을 주고 `decideSplit`을 호출합니다.
- `distributePhase`는 선택 팀 리더에게 두 묶음을 보여 주고 `decideChoice`를 호출합니다.
- `assignCardsWithinTeam`은 2인 팀에서 가져간 카드를 팀원끼리 나누게 합니다.
- `cashInPhase`는 환금, 처분, 도우미 사용을 처리합니다.
- `endPhase`는 승리 조건과 보유 한도를 확인합니다.

게임 상태는 기본적으로 `Game` 스레드에서만 바뀝니다. 그래서 규칙 적용 자체에는 복잡한 락을 두지 않습니다. 대신 사람, 봇, 네트워크 입력은 정해진 큐를 통해 게임 스레드로 제출됩니다.

## 렌더 루프와 게임 루프 분리

JavaFX UI는 JavaFX Application Thread에서만 안전하게 변경할 수 있습니다. 반면 `Game.play()`는 사람의 입력을 기다릴 수 있으므로 UI 스레드에서 실행하면 화면이 멈춥니다. 그래서 [GameBoardController.java](../src/main/java/com/oop/payday/controller/GameBoardController.java)는 `startGameThread`에서 별도 `game-loop` 스레드를 만들고 그 안에서 `game.play()`를 실행합니다.

```text
game-loop thread
  Game.play()
  -> GameListener callbacks
  -> awaitAnimations()

JavaFX Application Thread
  Platform.runLater(...)
  -> 화면 렌더링
  -> 사용자 입력 처리
```

`GameListener`는 모델에서 뷰로 가는 단방향 이벤트 통로입니다. 예를 들어 `onPhaseChanged`, `onDistributed`, `onCashIn`, `onGameOver` 같은 이벤트가 있습니다. `GameBoardController`는 이 이벤트를 받고 `Platform.runLater`로 UI 업데이트를 예약합니다.

애니메이션과 게임 진행은 `awaitAnimations()`로 동기화합니다. 게임 스레드는 특정 이벤트 뒤에 `listener.awaitAnimations()`를 호출하고, 컨트롤러는 현재 오버레이나 카드 이동 연출이 끝난 뒤 게임 스레드를 깨웁니다. 그래서 렌더링은 비동기지만, 규칙 진행은 연출을 기다리며 lockstep으로 맞출 수 있습니다.

이 동기화의 핵심은 [BoardAnimator.java](../src/main/java/com/oop/payday/controller/BoardAnimator.java)가 소유한 전역 오버레이 큐입니다. `GameBoardController`는 간부 배정, 슬쩍하기, 분배 이동, 도우미 발동처럼 화면 전체를 잠깐 덮는 연출을 큐에 넣습니다. `BoardAnimator`는 재생 중인지 여부와 대기 중인 연출 목록을 함께 관리해서 한 번에 하나의 오버레이만 실행하고, 각 연출이 끝나는 시점에 다음 연출로 바톤을 넘깁니다.

`awaitAnimations()`는 이 큐 위에 아주 얇게 얹혀 있습니다. 게임 스레드는 JavaFX 스레드에 "오버레이가 끝난 뒤 이 래치를 깨워 달라"는 콜백을 예약하고 기다립니다. 재생 중이거나 대기 중인 오버레이가 있으면 콜백은 별도 대기열에 보관되고, 큐가 완전히 비었을 때만 실행됩니다. 덕분에 `Game`은 "연출이 끝날 때까지 기다린다"는 계약만 알면 되고, 어떤 애니메이션이 몇 단계로 이어지는지는 UI 보조 컴포넌트 안에 갇힙니다. 동시에 대기 안내나 최신 패널 갱신처럼 오버레이 뒤에 나와야 하는 UI 작업도 같은 통로를 타므로, 긴 연출 중에 다음 화면이 먼저 튀어나오는 일을 막을 수 있습니다.

## 사용자 입력 루프

사람 플레이어는 [HumanPlayer.java](../src/main/java/com/oop/payday/player/HumanPlayer.java)입니다. `Game`이 사람에게 결정을 요구하면 `HumanPlayer.decideSplit`, `decideChoice`, `decideHelpers`, `decideTeamDistribution`은 `SynchronousQueue`에서 값을 기다립니다.

화면에서는 사용자가 버튼을 누르거나 카드를 선택합니다. 그 입력은 [InputGateway.java](../src/main/java/com/oop/payday/controller/InputGateway.java)를 통해 전달됩니다.

- 오프라인/호스트 모드에서는 [LocalInputGateway.java](../src/main/java/com/oop/payday/controller/LocalInputGateway.java)가 `HumanPlayer.provideXxx`를 직접 호출합니다.
- 클라이언트 모드에서는 [NetworkInputGateway.java](../src/main/java/com/oop/payday/controller/NetworkInputGateway.java)가 결정을 네트워크 메시지로 직렬화해 호스트에 보냅니다.

환금 단계는 조금 다릅니다. 환금은 한 번에 끝나는 선택이 아니라 여러 행동을 순서대로 제출할 수 있는 단계입니다. 그래서 `Game.cashInPhase`는 `BlockingQueue<Submission>`을 환금 인박스로 사용합니다. 사람, 봇, 네트워크 플레이어는 `CashSink`에 행동을 제출하고, 게임 스레드는 그 행동을 하나씩 꺼내 규칙에 적용합니다.

이 구조 덕분에 "모든 플레이어가 동시에 환금 단계에 들어가지만, 규칙 적용은 한 줄로 직렬화되는" 형태가 됩니다. UI 입장에서는 다른 플레이어의 도우미 발동이나 환금 결과를 보고 최신 스냅샷으로 다시 렌더링할 수 있습니다.

## Player 인터페이스

[Player.java](../src/main/java/com/oop/payday/player/Player.java)는 플레이어의 공통 상태와 의사결정 계약입니다.

주요 결정 메서드는 다음과 같습니다.

- `decideSplit(SplitContext)`
- `decideChoice(ChoiceContext)`
- `decideTeamDistribution(acquired, members)`
- `decideHelpers(options, chooseCount, HelperDraftContext)`
- `beginCashIn(CashInContext, opponentCoins, CashSink)`

`Game`은 구체 타입을 모른 채 이 메서드만 호출합니다. 이 덕분에 다음 구현들이 같은 게임 루프에 붙습니다.

- `HumanPlayer`: UI 스레드가 넘겨 준 결정을 기다립니다.
- `BotPlayer`: `BotStrategy`에 결정을 위임합니다.
- `NetworkPlayer`: 호스트 측에서 원격 클라이언트를 대신하는 대리자입니다.
- `MirrorPlayer`: 클라이언트가 받은 공개 상태를 렌더링하기 위한 읽기용 플레이어입니다.

이 설계는 네트워크 플레이 확장에 특히 중요합니다. 호스트의 `Game`은 원격 사용자를 특별 취급하지 않고 `NetworkPlayer`의 결정만 기다립니다. 원격 응답은 reader 스레드가 받아 `NetworkPlayer.provideXxx`로 전달합니다.

## 봇 구조

봇의 확장점은 [BotStrategy.java](../src/main/java/com/oop/payday/bot/BotStrategy.java)입니다. [BotPlayer.java](../src/main/java/com/oop/payday/player/BotPlayer.java)는 실제 플레이어 객체이고, 전략 객체는 결정을 계산하는 두뇌입니다.

현재 기본 전략은 [S8BotStrategy.java](../src/main/java/com/oop/payday/bot/S8BotStrategy.java)입니다. S8은 공개 정보 카드 카운팅, 상대 선택 확률 모델, 실현 코인 기반 종반 판단, 2v2 팀 분배, 도우미 드래프트 보정을 사용합니다.

난이도별 봇은 [DifficultyAdjustedBotStrategy.java](../src/main/java/com/oop/payday/bot/DifficultyAdjustedBotStrategy.java)입니다.

- 어려움은 S8을 그대로 사용합니다.
- 중간은 상대 보관, 버림 더미, 도우미, 간부 정보 일부를 가려 S8을 사용합니다.
- 쉬움은 중간 수준 판단에 단순 분할과 공개 코인 기반 선택을 확률적으로 섞습니다.

`BotPlayer`는 생각 시간도 담당합니다. 전략은 `thinkDelay()`와 `cashInThinkDelay()`로 대기시간 정책만 제공하고, 실제 `Thread.sleep`은 `BotPlayer`가 수행합니다. 환금 단계에서는 봇이 가상 스레드에서 계획을 세운 뒤 행동을 하나씩 `CashSink`에 제출합니다. 그래서 봇의 생각 시간이 게임 스레드를 직접 막지 않습니다.

LLM 봇은 [LlmBotStrategy.java](../src/main/java/com/oop/payday/bot/LlmBotStrategy.java)입니다. LLM은 분할/선택 대사와 결정을 제안하지만, 불법 수나 호출 실패가 있으면 내장 S8 결정으로 조용히 폴백합니다. 환금과 팀 분배처럼 합법성 부담이 큰 영역은 S8에 위임합니다.

## 네트워크 구조

네트워크 플레이는 호스트 권위 구조입니다. 실제 `Game`은 호스트에서만 실행됩니다. 클라이언트는 호스트가 보내는 공개 상태와 이벤트를 미러링하고, 자기 입력만 호스트로 보냅니다.

호스트 쪽 주요 파일은 다음과 같습니다.

- [GameServer.java](../src/main/java/com/oop/payday/net/GameServer.java): TCP 서버, 클라이언트 수락, reader 스레드, 결정 메시지 라우팅을 담당합니다.
- [NetworkPlayer.java](../src/main/java/com/oop/payday/player/NetworkPlayer.java): 호스트 게임 안에서 원격 클라이언트를 대표하는 `Player`입니다.
- [NetworkBroadcaster.java](../src/main/java/com/oop/payday/net/NetworkBroadcaster.java): `GameListener` 이벤트를 네트워크 메시지로 직렬화해 클라이언트에 보냅니다.
- [FanOutGameListener.java](../src/main/java/com/oop/payday/net/FanOutGameListener.java): 호스트 화면과 네트워크 브로드캐스터에 같은 게임 이벤트를 나눠 보냅니다.

클라이언트 쪽 주요 파일은 다음과 같습니다.

- [GameClient.java](../src/main/java/com/oop/payday/net/GameClient.java): 호스트에 접속하고 reader 스레드에서 메시지를 수신합니다.
- [ClientMirror.java](../src/main/java/com/oop/payday/net/ClientMirror.java): 수신한 `PublicBoardState`로 클라이언트 화면용 `Team`/`Player`/카드 객체를 갱신합니다.
- [NetworkInputGateway.java](../src/main/java/com/oop/payday/controller/NetworkInputGateway.java): UI 입력을 `NetMessage`로 바꿔 호스트에 보냅니다.

호스트가 클라이언트에게 보내는 정보는 관점별로 다릅니다. 자기 팀 도우미는 볼 수 있지만 상대 팀 도우미는 사용되기 전까지 숨깁니다. 꾀부리기 손패, 도우미 후보, 환금 패널 같은 비공개 입력 요청은 해당 플레이어를 소유한 클라이언트에게만 전송합니다.

입력 요청에는 request id가 붙습니다. 클라이언트는 응답에 같은 id를 echo하고, 호스트의 `NetworkPlayer`는 현재 기다리는 요청과 id 및 종류가 맞을 때만 수락합니다. 이 방식으로 재시작 직전의 늦은 응답, 중복 응답, 다른 종류의 응답을 버립니다.

## 패키지 안내

| 패키지 | 역할 |
|---|---|
| `app` | JavaFX 애플리케이션 시작과 화면 전환입니다. |
| `controller` | FXML 컨트롤러, 보드 렌더링, 입력 게이트웨이, 애니메이션 orchestration입니다. |
| `view` | 카드, 패널, 규칙서, 조합표 같은 재사용 UI 빌더입니다. |
| `game` | 라운드 상태 머신, 팀, 설정, 리스너 계약입니다. |
| `player` | 사람, 봇, 원격, 미러 플레이어 구현입니다. |
| `decision` | 분할, 선택, 환금, 팀 분배에 필요한 입력/출력 record입니다. |
| `model` | 카드, 덱, 도우미, 간부, 세트 평가 등 순수 규칙 모델입니다. |
| `bot` | S8 전략, 난이도 래퍼, LLM 전략, 봇 평가 유틸입니다. |
| `net` | TCP 메시지, DTO, 호스트 서버, 클라이언트 미러, 브로드캐스터입니다. |
| `log` | 플레이 로그 기록입니다. |

## 확장 지점

새 봇을 만들려면 `BotStrategy`를 구현하고 `BotKind`에 노출하면 됩니다. `BotPlayer`와 `Game`은 새 전략의 구체 타입을 알 필요가 없습니다.

새 플레이어 입력 방식을 붙이려면 `Player` 구현과 `InputGateway` 구현을 추가하면 됩니다. 현재 로컬 사람과 네트워크 클라이언트가 같은 구조를 사용합니다.

새 규칙이나 카드 효과를 추가하려면 `model` 계층에 규칙을 넣고, `Game`의 단계 처리와 `GameListener` 이벤트가 필요한지 확인해야 합니다. UI 표현만 바꾸는 작업은 대체로 `controller`와 `view`에 머뭅니다.

네트워크로 보내야 하는 공개 정보가 늘어날 때는 `PublicBoardState`와 DTO를 먼저 검토해야 합니다. 봇 전용 추론이나 비공개 정보가 DTO에 섞이면 안 됩니다. 공개 상태, 소유자 전용 요청, 호스트 내부 판단을 분리하는 것이 이 구조의 중요한 안전선입니다.
