# 봇 전략 현황과 로드맵

봇 의사결정 구조의 현재 상태와 앞으로 할 일을 정리한 살아있는 문서다.

## 현재 구조

봇은 `BotStrategy` 인터페이스로 모든 의사결정을 위임한다(전략 패턴).

- `decideSplit` / `decideChoice` / `decideHelpers` / `planCashIn` / `decideTeamDistribution`

구현체와 유틸:

- `HeuristicBotStrategy` — 규칙 기반. 세트 코인 가치만 보고 단순·안정적으로 판단.
- `S1BotStrategy` — 점수 기반 기준선(baseline). 환금 조합 최적화 + 카드 잠재력 평가.
- `S2BotStrategy` — S1 + 분할 블러핑 명시화·승리 임박 환금.
- `S3BotStrategy` — S2 + 상황 컨텍스트(보유 카드·양 팀 코인). 시너지 평가, 적응형 선택자 모델, 승리 확정 분할, 상대 임박 환금. **현재 버전.**
- `BotCardEvaluator` — 카드 잠재력 점수, 버릴 카드 점수(`potentialScore`/`bundleScore`/`chooseDiscard`).
- `CashInPlanOptimizer` — 환금 단계에서 겹치지 않는 세트 조합을 탐색해 총점이 최대인 계획 생성.
- `BotKind` — 대기실 봇 슬롯 선택지(현재 `SMART`→S1, `HEURISTIC`). **S2·S3는 아직 미노출**(A/B 전용).

봇 전용 컨텍스트(`SplitContext`/`ChoiceContext` + 환금의 `opponentCoins` 인자)는 네트워크 미러(`CashInContextDto` 등)와 분리돼 직렬화 경로를 타지 않는다.

검증: `HeadlessBotGameTest` 의 `S1vsS2SeedReport`·`S2vsS3SeedReport` 가 고정 seed 로 맞붙여 승률·평균 코인·무효 환금 수를 측정한다(표본 수는 `-DbotSeedCount` 로 조절, 기본 40판).

## 구현 완료

| 항목 | 상태 | 위치 |
|---|---|---|
| 환금 계획 최적화(조합 탐색·도우미 보너스·저주 무료 처리·와일드 페널티·남은 잠재력) | ✅ | `CashInPlanOptimizer` |
| 카드 잠재력 점수(같은 숫자/같은 색 연속/와일드/저주 매칭/5연속) | ✅ | `BotCardEvaluator` |
| 버릴 카드 선택 개선 | ✅ | `BotCardEvaluator.chooseDiscard` |
| 환금 시점 상황형 도우미 사용(VIPER·TUSKER·DOUG·JUNK·CROC EV 복사·반응 도우미) | ✅ | `CashInPlanOptimizer` |
| 분할 블러핑 명시화(미끼 유도력 항, `LURE_WEIGHT`) | ✅ | `S2BotStrategy.scoreSplit` |
| 승리 임박 환금(팀 코인이 승리 코인 70% 이상이면 와일드·잠재력 무시) | ✅ | `CashInPlanOptimizer.plan(ctx, winAware)`, `CashInContext.winningCoins` |
| 잠재력 반영 분할·선택 | ✅ | `S1/S2BotStrategy` |
| 봇 성능 A/B 테스트 + 무효 행동 검증 | ✅ | `HeadlessBotGameTest` |
| 보유 카드 시너지 분할·선택(`potentialScore(holdings∪bundle) − potentialScore(holdings)`, 한계 기여로 한 번만 계산) | ✅ | `S3BotStrategy.myKeepValue` |
| 적응형 선택자 모델(긴박도 `tighten`: 마지막 30% 구간에서만 종반 견제·잠재력 페이드 활성) | ✅ | `S3BotStrategy.tighten` |
| 승리 확정 분할/즉시 승리 선택(`bestCashCoin(holdings∪b) ≥ myNeed`, 양쪽 보장 시 압도 보너스) | ✅ | `S3BotStrategy.secures` |
| 상대 임박 환금(양 팀 중 하나라도 70% 이상이면 보존·잠재력 무시) | ✅ | `CashInPlanOptimizer.plan(ctx, opponentCoins)` |

## 측정 현황

- **S2 vs S1, 500판**: S2 승률 **53.0%**(265/235), 평균 코인 S2 29.13 / S1 28.69, 무효 환금 **0건**.
- **S3 vs S2, 600판**(`-DbotSeedCount=300`): S3 승률 **54.3%**(326/274), 평균 코인 거의 동률, 무효 환금 **0건**. z≈2.1(한쪽 p≈0.02).
- 해석: 컨텍스트 개선은 좁은 상황(종반·보유 시너지)에서만 들어가 우위가 작다(≈3~4%p). 초기 구현에서 `tighten` 이 중반부터 선형으로 켜져 보수성이 새어 손해였으나(≈49%), **종반 구간으로 한정**한 뒤 53%대로, 종반 maximin 을 켜 54%대로. 무효 행동 견고성은 확실(수천 판 0건).
- **자기복제 스윕**(`S3ParamSweep`): 파라미터만 바꾼 S3끼리 대결해 순진한 S2에 과적합하지 않도록 튜닝. DEFAULT vs DEFAULT 대조군이 정확히 50.0% (노이즈 바닥 확인). 어떤 변형도 DEFAULT 를 유의하게 못 이김 → 현재 튜닝이 이미 거의 최적. `maximinWeight`(종반 견제 대비 견고성, 사람 상대)만 자기복제 중립~+이고 S2 상대 무회귀라 **기본값 ON**(종반에만 작동).
- 주의: 환금이 두 봇 가상 스레드로 동시에 진행돼 제출 순서에 비결정성이 있다 → 소표본(40판) 승률은 run 마다 흔들린다. 신호는 대표본에서 본다.

## 남은 작업

### 1. 파라미터 튜닝 (1차 완료 — 자기복제 스윕)
S3 가중치를 `S3Tuning` 레코드로 묶고 `S3ParamSweep`(파라미터만 바꾼 S3끼리 A/B)로 스윕했다. **순진한 S2가 아니라 동급 상대에게 통하는 방향**을 찾기 위함(사람 상대 프록시). 결과: `endzonePct`(30), `lureWeight`(40), `denyWeight`(100) 모두 현재값이 거의 최적, `maximinWeight` 만 ON 이 이득이라 기본값 채택. 남은 곁가지: 환금 임박 임계값(`CashInPlanOptimizer` 의 70%)·`WIN_SECURE` 스케일은 아직 미스윕(영향 작을 것으로 추정). `-DbotSeedCount` 대표본으로 추가 확인 가능.

### 2. 상황형 도우미 드래프트 (보류)
**검토 결과 현 구조에서는 의미 있는 컨텍스트를 전달하기 어렵다.**
`setupHelpers()` 는 게임 시작 직후 한 번만 불리며, 이 시점에 보관 카드는 항상 비어 있다.
역할(분할/선택팀) 정보도 매 라운드마다 교체되므로 초기 역할 기반 판단은 근거가 약하다.
유의미한 패/세트 정보가 생기는 시점(환금 단계)엔 이미 `CashInContext` 가 해당 역할을 한다.
→ 우선순위를 3번으로 낮추고, 게임 구조(라운드 중 도우미 재선택 등)가 변경되면 재검토한다.

### 3. 점수 상황 반영 확장 → **S3BotStrategy** ✅ 완료

컨텍스트를 확장해 **보유 카드 시너지 + 양 팀 코인 상황**을 반영했다.

**컨텍스트(봇 전용, 비직렬화):**
- `decideSplit(List<Card>)` → `decideSplit(SplitContext)` : `hand` + `holdings` + `myCoins` + `opponentCoins` + `winningCoins`
- `decideChoice(ChoiceView)` → `decideChoice(ChoiceContext)` : `ChoiceView`(유지) + `holdings` + `myCoins` + `opponentCoins` + `winningCoins`
- 환금은 `beginCashIn(snapshot, opponentCoins, sink)` / `planCashIn(CashInContext, opponentCoins)` 로 상대 코인만 별도 인자 전달 — `CashInContext`·DTO·`NetworkBroadcaster`·`GameClient` 무수정(네트워크 미러 비오염).

**S3 로직(적응형 선택자 모델):** *"선택자는 적이고, 게임이 조일수록 적대적으로 고른다"* 를 연속 함수로 풀었다.
- 시너지 = `potentialScore(holdings∪bundle) − potentialScore(holdings)`(한계 기여 — 코인은 전액, 잠재력은 한 번만, 이중 계산 없음).
- `tighten`(0~100): 양 팀 중 가까운 쪽 기준, **마지막 30% 구간에서만** 상승. 종반에 잠재력·블러핑 페이드 + 견제 활성, 중반엔 0(보수성 차단).
- 승리 확정 분할/즉시 승리 선택: `bestCashCoin(holdings∪b) ≥ myNeed` 인 묶음에 압도 보너스(양쪽 보장 시 선택자가 못 막음 = `myCoins` 활용의 핵심).
- `planCashIn`: 양 팀 중 하나라도 70% 이상이면 와일드·잠재력 무시 즉시 환금.

**변경 범위(실제):** `decision` 에 `SplitContext`/`ChoiceContext` 신설, `Player`/`BotStrategy` 시그니처(S1·S2·Heuristic·Human·Network·Mirror 컴파일 맞춤), `Game.java` 컨텍스트 빌드 + `opponentCoinsOf`, `S3BotStrategy` 신설, `HeadlessBotGameTest.S2vsS3SeedReport`. **DTO·네트워크는 무수정.**

> 다음 후보(1번 파라미터 튜닝): `WIN_SECURE`·`LURE_WEIGHT`·종반 구간(현재 30%)·`tighten` 곡선을 `-DbotSeedCount` 대표본으로 스윕.

### 4. 난이도별 봇
`BotKind` 에 `EASY`/`NORMAL`/`HARD` 추가. 쉬움은 S2 결정에 확률적 무작위·지연을 섞는 래퍼 전략으로 구현(일부러 약하게 = 재미). 보통은 S2 그대로, 어려움은 3·5의 개선 반영.

### 5. 매우 어려움 (장기)
상대 공개 카드 추적, 라운드 역할(분할팀/선택팀)에 따른 장기 전략, 시뮬레이션 기반 선택. 3번의 컨텍스트 확장이 선행되어야 하며 범위가 가장 크다.

## 설계 원칙 (유지)

- 새 전략은 `BotStrategy` 인터페이스를 유지한다.
- 게임 엔진 규칙 로직과 봇 평가 로직을 분리한다(규칙 판정은 `SetEvaluator`/`CashInEvaluator`).
- 각 액션 생성 전에 검증해 무효 행동을 내지 않는다(현재 500판 0건 — 회귀 시 테스트가 잡는다).
- 성능 비교 기준선으로 `S1BotStrategy` 를 삭제하지 않는다. 새 실험은 별도 클래스(S3…)로 추가해 A/B 한다.
- 전략이 너무 완벽하면 재미가 떨어지므로 난이도별 변형을 둔다.
