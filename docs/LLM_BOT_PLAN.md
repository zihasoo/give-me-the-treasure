# LLM 봇 계획 (말하는 상대 — 토이)

LLM 기반 봇 계획을 정리한 살아있는 문서다. 전략 봇(→ [BOT_STRATEGY_PLAN.md](BOT_STRATEGY_PLAN.md))과는 **목적이 다르다.**

## 목적

- **승률이 목표가 아니다.** 1v1로 사람과 붙으면서 **대사(인게임 도발/혼잣말)를 치는 "말하는 상대"** 가 목적이다(장난감용).
- 제미나이/GPT 포켓몬 대결처럼, 잘 두는 것보다 **성격 있고 예측 불가한 상대**가 핵심 재미.
- 컨셉(페르소나): **무뚝뚝한 츤데레.** 평소 짧고 퉁명스럽게 굴다가, 좋은 수/위기 때 본심이 슬쩍 새는 톤.

## 가능성 근거

`BotStrategy` 인터페이스가 이미 LLM 구현을 염두에 두고 설계돼 있어, **새 아키텍처 없이 구현체 하나 추가**면 된다. 게임 엔진·`BotPlayer`·네트워크는 손대지 않는다.

- 결정 객체가 직렬화하기 좋은 record다: `SplitDecision` / `ChoiceView` / `CashInContext` / `CashInAction`.
- `decideSplit/Choice/Helpers` 는 게임 스레드에서 호출되고 `BotPlayer.think()` 가 이미 2~4초 블록한다 → LLM 네트워크 지연이 "생각하는 텀"으로 자연스럽게 흡수된다.
- `planCashIn` 은 봇 전용 가상 스레드에서 돌아 게임 스레드를 막지 않는다.

## 구조 (최소)

```
LlmBotStrategy implements BotStrategy
 ├─ 매 결정마다 Claude 1회 호출 → { move, line } 동시 수신 (structured output)
 ├─ move 검증 → 불법이면 조용히 폴백(S2BotStrategy)        ← 게임이 깨지지 않음
 └─ say.accept(line)  ← 생성자로 주입받은 Consumer<String> (대사 싱크)
```

- 환금 최적화는 **굳이 LLM에 안 맡긴다.** 토이라 승률 무관 → LLM이 그냥 두되 검증만 통과시키고, 무효면 폴백. (원하면 나중에 `planCashIn` 만 `CashInPlanOptimizer` 위임하는 하이브리드로 격상 가능.)
- **무효 행동 금지** 원칙 유지: `SplitDecision.isValid()` / `SetEvaluator` / `CashInEvaluator` 로 검증 → 실패 시 폴백. 회귀는 `HeadlessBotGameTest` 가 잡는다.

## 대사 채널 (이미 존재)

| 위치 | 용도 |
|---|---|
| `GameListener.onMessage(String)` | 상태바/로그에 한 줄. 가장 단순, 이미 UI 연결됨. |
| `BoardAnimator.playHelperEffect` 의 떠오르는 라벨 패턴 | 봇 머리 위 **말풍선**. 도우미 효과 연출(~513줄)을 재활용. (권장) |

## 배선

- `LlmBotStrategy(Consumer<String> say, Persona persona)` 생성자 추가.
- 1v1 로컬 게임 진입점에서 컨트롤러가 `say = line -> 보드에 말풍선 띄우기` 를 주입. (`BotKind.create()` 는 인자가 없으니 로컬 1v1 한정으로 별도 생성하거나, 대사 싱크를 받는 팩토리를 추가.)
- 타이밍은 공짜: decide\* 가 게임 스레드에서 블록 허용 → **"잠깐 생각 → 한 마디 → 카드 둠"** 흐름이 자연스럽게 나온다.

## 구현 기술

- Anthropic **Java SDK** (`com.anthropic:anthropic-java`) — Gradle/mavenCentral 에 의존성 한 줄.
- 모델 `claude-opus-4-8`, adaptive thinking. 짧은 대사라 토큰·호출 수 모두 적다.
- **structured output** 으로 `{ "move": {...}, "line": "..." }` 를 한 번에 받아 파싱 안전 + 호출 1회.
- 페르소나·짧은 히스토리를 시스템 프롬프트/컨텍스트로 주면 맥락 있는 도발이 나온다(예: *"또 뒷면 한 장으로 흔들려고? …흥, 안 통해."*).

## 주의점

- **API 키 없거나 네트워크 불가 → 자동 비활성/폴백.** 기본 봇은 오프라인이지만 LLM 봇은 네트워크가 필요하다. 어디까지나 선택형으로 둔다.
- 비용·지연: 1v1이라 부담 적음. 대규모 A/B(500판)에는 부적합 — 측정 목적이 아니므로 상관없음.

## 단계별 작업 (우선순위)

1. **골격**: `LlmBotStrategy`(검증+폴백) + 무뚝뚝한 츤데레 페르소나 프롬프트 + structured output 파싱.
2. **대사 연출**: `BoardAnimator` 말풍선 재활용 + `say` 싱크 배선.
3. **1v1 진입점**: 로컬에서 사람 vs LLM 봇으로 한 판 도는 경로(키 주입 포함).
4. (선택) 내 턴에도 리액션 한 마디.
5. (선택) 하이브리드 격상: `planCashIn` 만 `CashInPlanOptimizer` 위임.

## 설계 원칙 (유지)

- `BotStrategy` 인터페이스를 유지한다(게임 엔진 불변).
- 무효 행동을 절대 내지 않는다 — 항상 검증 후 폴백.
- LLM 의존(네트워크·키)은 선택형으로 격리한다.
