# 봇 전략 현황과 로드맵

봇 의사결정 구조의 현재 상태와 앞으로 할 일을 정리한 살아있는 문서다.
**이 문서만 보고 다음 작업을 코드로 옮길 수 있도록** 진단·구현 위치·알고리즘·검증을 함께 적는다.

---

## 0. 한 줄 요약 (지금 읽을 사람에게)

**S6 가 최신 버전이다(§4.6).** 봇끼리는 이겼지만(S5 vs S4 56%) **사람은 못 이기는** 약점 세 가지 — ① 분할 호구 + ② 선택 오판(순진한 선택자 가정), ③ 저주·고가치 보물 의도적 플레이 부재 — 를 코드로 진단하고 교정했다. 함께 **플레이 로그 파일 출력**(`PlayLogWriter`)을 붙여, 이제 강함의 척도를 봇끼리 승률이 아니라 **사람 vs 봇 로그 분석**으로 바꾼다(§4.3 정성 평가의 실현). 회귀 안전망상 S6 vs S5는 72.5%/무효 환금 0이지만, 이 숫자는 사람 상대 강함의 증명이 아니다. 이전 진행: S5는 보류 판단을 연속화해 S4 대비 56%, S4는 환금 보류+상대 견제로 S3 대비 79%(최대 도약)였다. → [§4 우선 작업](#4-우선-작업-로드맵).

---

## 1. 현재 구조

봇은 `BotStrategy` 인터페이스로 모든 의사결정을 위임한다(전략 패턴).

- `decideSplit(SplitContext)` / `decideChoice(ChoiceContext)` / `decideHelpers` / `planCashIn(CashInContext, opponentCoins)` / `decideTeamDistribution`

구현체와 유틸 (`com.oop.payday.bot`):

| 클래스 | 역할 |
|---|---|
| `HeuristicBotStrategy` | 규칙 기반. 세트 코인 가치만 보고 단순·안정. 비교 하한선. |
| `S1BotStrategy` | 점수 기반 기준선(baseline). 환금 조합 최적화 + 카드 잠재력 평가. **삭제 금지**(A/B 기준선). |
| `S2BotStrategy` | S1 + 분할 블러핑 명시화 + 자기 팀 승리 임박 환금. |
| `S3BotStrategy` | S2 + 상황 컨텍스트(보유 카드·양 팀 코인). 시너지·적응형 선택자 모델·승리 확정 분할·상대 임박 환금. |
| `S4BotStrategy` | S3 + 환금 보류(§4.1) + 상대 holdings 견제(§4.2). S3 대비 74.0% 승률. |
| `S5BotStrategy` | S4 + 보류 판단 개선(§4.1-S5). S4 대비 55.0% 승률. **A/B 기준선으로 동결.** |
| `S6BotStrategy` | S5 + 사람 상대 약점 교정(§4.6). 현실적 선택자 모델·상시 maximin·뒷면 저주 떠넘김·다중 세트 보류·환금 도우미 강화. **현재 최신 버전.** |
| `BotCardEvaluator` | 카드 잠재력 점수(`potentialScore`)·세트 코인(`bestCashCoin`)·버릴 카드(`chooseDiscard`). |
| `CashInPlanOptimizer` | 환금 단계에서 겹치지 않는 세트 조합을 탐색해 총점 최대 계획 생성. `planWithHold`(S4), `planWithHoldS5`/`planWithHoldS6`(S5/S6 공유 본문 + 도우미 강화 플래그) 각각 보류 전략 포함. |
| `BotKind` | 대기실 봇 슬롯 선택지(`S1`·`S2`·`S3`·`S4`·`S5`·`S6`·`HEURISTIC` 노출). |
| `PlayLogWriter` | 오프라인 게임 진행을 사람 가독 로그로 `logs/play-*.log` 에 기록(`GameListener`). 사람 vs 봇 사후 분석용(§4.6). |

**컨텍스트 분리 원칙(중요):** 봇 전용 정보 중 네트워크 동기화가 불필요한 값(`opponentCoins` 등)은 `CashInContext`에 넣지 않고 `beginCashIn(snapshot, opponentCoins, sink)` 의 **별도 인자**로 흘린다. 반면 공개 정보이고 직렬화해도 무방한 값(`opponentHoldings` 등)은 S5부터 `CashInContext` 에 포함하고 `CashInContextDto` 에도 추가해 네트워크로 전달한다.

**검증:** `HeadlessBotGameTest` 의
- `S1vsS2SeedReport`·`S2vsS3SeedReport`·`S3vsS4SeedReport`·`S4vsS5SeedReport` — 고정 seed 로 선후공 교대 대전, 승률·평균 코인·무효 환금 측정.
- `S3ParamSweep` — 파라미터만 바꾼 S3끼리 자기복제 A/B.
- 표본 수는 `-DbotSeedCount`(기본 20 seed × 2 = 40판).

---

## 2. 핵심 진단 (S4 이전 분석 — A·B 해소됨)

> 이 절은 **S4 개발 전**에 "업그레이드가 왜 체감이 안 되는가"를 분석한 기록이다. 세 구멍 중
> **(A) 즉시 환금 / (B) 상대 견제 부재는 S4·S5(§4.1·§4.2)로 해소**됐고, 그 결과 승률이 크게 올랐다
> (S4 vs S3 79%). **(C) 측정의 구멍만 아직 유효**하며 §4.3에서 다룬다. 진단 맥락 보존을 위해 원문을 남긴다.

세 가지가 겹친다. **(A)(B)는 전략 내용의 구멍, (C)는 그걸 못 잡아낸 측정의 구멍.**

### (A) ✅ [해소 → §4.1] 봇은 환금을 미루지 않는다 — "즉시 환금 머신"

규칙서의 환금표는 **초선형(super-linear)** 이다:

| 세트 | 3장 | 4장 | 5장 |
|---|---|---|---|
| 같은 숫자 | 5 | **10** | (불가) |
| 연속 | 4 | 6 | **10** |
| 연속+같은 색 | 6 | 9 | **15** |

→ 작은 세트를 그때그때 내는 것보다 **한도가 허락하는 한 모아서 한 방에 내는 게 코인 효율이 압도적**이다(예: 연속+같은색 3장 6코인을 참고 5장까지 키우면 15코인).

그런데 `CashInPlanOptimizer.findBestPlan` 의 기준점은:

```java
// CashInPlanOptimizer.java
// "아무것도 환금 안 함" 기준점 = 0 (코인 환금은 항상 이득이므로 항상 양수여야 함)
Plan best = new Plan(List.of(), 0);
```

즉 **"환금 가능하면 무조건 지금 환금"** 이 봇의 정책이다. "이번 턴엔 안 내고 다음 턴에 더 키운다"는 선택지를 **평가 자체를 안 한다.** 봇이 가진 종반 로직(`isEndgame`: 양 팀 중 하나가 목표 70% 도달 시 즉시 환금)은 사용자 직관의 단서("당장 한도가 넘치는 게 아니면")의 **절반(상대 임박 시 질러)만** 구현한 것이고, **나머지 절반(그 전엔 모아라)이 통째로 빠져 있다.**

> 결과: 사람이 보면 "왜 저렇게 쪼잔하게 빨리빨리 환금하지?" 로 보이는 바로 그 행동. **이것이 체감 부재의 1순위 원인.**

### (B) ✅ [해소 → §4.2] 봇은 상대가 뭘 모으는지 안 본다 — 색 연속 견제 불가

분할의 핵심은 *상대가 같은 색 연속(최고 코인 세트)을 완성하지 못하도록 끊어서 나누거나 뒷면으로 숨기는 것*이다. 이건 **상대의 현재 보관 카드(holdings)** 를 알아야 한다. 규칙상 보관 영역은 앞면 공개라 정보는 **존재**한다(`Player.holdings()` 로 접근 가능).

그러나 `SplitContext`/`ChoiceContext` 에는 `opponentCoins`(숫자 하나)만 있고 **`opponentHoldings` 가 없다.** 그래서 S3 의 견제(`scoreSplit` 의 `theirs = standaloneValue(상대 묶음)`)는 **묶음 자체 안에서의** 잠재력만 본다. "이 빨강3·6 을 한 묶음에 주면, 빨강4·5 를 이미 쥔 상대가 5장 연속+같은색(15코인)을 완성한다"를 **판단할 수 없다**(상대 패를 안 보므로). 게다가 그 단독 잠재력 견제마저 종반엔 `*(100-tighten)/100` 으로 **꺼진다.**

> 결과: 사용자가 "가장 중요하다"고 적은 견제를 봇은 원천적으로 못 한다. **체감 부재의 2순위 원인.**

### (C) ⏳ [유효 → §4.3] 측정이 "사람 상대 강함"을 보증하지 못한다

- **전부 봇 vs 봇이다.** 사람 상대 측정이 없는데, 정작 불만은 "사람이 할 때 체감"이다. 봇끼리의 4%p 우위는 사람 경험과 연결고리가 없다.
- **예측이 자기충족된다.** S3 분할은 "상대는 공개 코인 최대 쪽을 가져간다"고 가정하는데(`scoreSplit`), S1/S2 의 `decideChoice` 가 정확히 그렇게 고른다. 봇은 **자기 예측이 100% 맞는 세계**에서 튜닝됐다. 사람은 뒷면·자기 패 시너지·블러핑 읽기로 다르게 고른다 → lure(미끼) 항의 사람 상대 효과는 측정된 적이 없다.
- **self-play "거의 최적" 해석 주의.** "어떤 변형도 DEFAULT 를 못 이김"은 *최적 도달*이 아니라 *이 파라미터 축으로는 더 짤 게 없다(수확 체감)* 는 뜻이다. 정책 자체가 바뀌면 지형이 통째로 달라진다.
- 환금 동시진행 비결정성(가상 스레드 제출 순서)까지 더하면 소표본 승률 노이즈가 크다 → 신호는 대표본에서만.

---

## 3. 구현 완료 (기록 — 더 손댈 필요 없음)

| 항목 | 위치 |
|---|---|
| 환금 계획 최적화(조합 탐색·도우미 보너스·저주 무료 처리·와일드 페널티·남은 잠재력) | `CashInPlanOptimizer` |
| 카드 잠재력 점수(같은 숫자/같은 색 연속/와일드/저주 매칭/5연속) | `BotCardEvaluator.potentialScore` |
| 버릴 카드 선택(`chooseDiscard`) | `BotCardEvaluator` |
| 환금 시점 상황형 도우미(VIPER·TUSKER·DOUG·JUNK·CROC EV 복사·반응 도우미) | `CashInPlanOptimizer` |
| 분할 블러핑 명시화(미끼 유도력 `LURE_WEIGHT`) | `S2BotStrategy.scoreSplit` |
| 자기 팀 / 양 팀 승리 임박 환금(`isImminent`/`isEndgame`, 70% 임계) | `CashInPlanOptimizer` |
| 보유 카드 시너지 분할·선택(한계 기여 `synergy`) | `S3BotStrategy.myKeepValue` |
| 적응형 선택자 모델(긴박도 `tighten`, 종반 구간에서만) | `S3BotStrategy.tighten` |
| 승리 확정 분할/즉시 승리 선택(`secures`) | `S3BotStrategy` |
| 파라미터 자기복제 스윕(`S3Tuning`/`S3ParamSweep`) | `HeadlessBotGameTest` |
| 무효 환금 견고성(수천 판 0건, 회귀 시 테스트가 잡음) | `HeadlessBotGameTest` |
| 환금 보류 전략(`planWithHold`, `pickBestToHold`, `growthCoin`, `hasGrowthRoom`) | `CashInPlanOptimizer` |
| 상대 holdings 견제(`oppSynergy`, `SplitContext`/`ChoiceContext` 확장, `Game.teamHoldings`) | `S4BotStrategy`, `SplitContext`, `ChoiceContext`, `Game` |
| S3 vs S4 A/B 리포트(`S3vsS4SeedReport`) | `HeadlessBotGameTest` |
| 보류 판단 정밀화(`planWithHoldS5`, `growthValue`, `expectedStepTurns`, `nextTargets`) — 이진→연속, 상대 손패 반영 | `CashInPlanOptimizer` |
| `CashInContext` / `CashInContextDto`에 `opponentHoldings` 추가 및 직렬화 경로 업데이트 | `CashInContext`, `CashInContextDto`, `Game`, `NetworkBroadcaster`, `GameClient` |
| S4 vs S5 A/B 리포트(`S4vsS5SeedReport`) | `HeadlessBotGameTest` |

### 완료 상세 — 전략 축(S4·S5)

로드맵(§4)에서 완료되어 이 절로 옮긴 항목이다. 위 표가 위치 요약, 아래가 설계 근거다.

#### 환금 타이밍 — 보류 전략 (S4, 옛 §4.1)

> 사용자 원문: *"환금 상황에서 당장 한도가 넘치는 게 아니면 환금을 최대한 미루는 게 무조건 이득임 — 다음 분배에서 카드가 넘쳐도 그 턴의 환금까지는 카드를 모두 보유할 수 있고, 꾀부리기/분배 단계에서 상대가 견제해야 할 카드가 많아져 부담이 됨."*

- `CashInPlanOptimizer.planWithHold` + `pickBestToHold` + `growthCoin` + `hasGrowthRoom`.
- 베스트 플랜 중 성장 여지가 있고 보류 후 한도 이하인 세트 하나를 이번 턴 환금에서 제외.
- 종반(`isEndgame`) 또는 이기는 세트(coin ≥ myNeed)는 절대 보류하지 않음.
- `S4BotStrategy.planCashIn` 이 호출. S3 의 `plan(context, opponentCoins)` 는 A/B 기준선으로 유지.

#### 보류 판단 정밀화 — 기대 획득 턴 기반 연속 점수 (S5, 옛 §4.1-S5)

S4의 `pickBestToHold` 는 `growthCoin`(이진: 한 단계 코인 증분)으로 보류 후보를 줄 세우고 버림 더미 유무로만 필터해, ① 2장→5장 잠재력을 낮게 보고 ② 상대 손패에 있는 카드의 긴 대기를 구분하지 못했다. S5가 개선:

- `growthValue(set, holdings, discardPile, opponentHoldings)` — (max까지 코인 증분) / (누적 예상 턴) 으로 단계별 기대값 계산, 가장 좋은 목표 크기를 선택.
- `expectedStepTurns` — 필요 카드가 버림 더미(`DISCARD_TURNS=1.5`) / 상대 손패(`OPP_TURNS=5.0`) / 미지 덱(`unknownTreasure / (unknownAlts × 2.5)`) 중 어디 있는지로 예상 턴을 3등급으로 계산.
- `nextTargets(set)` — SAME_NUMBER·RUN·RUN_SAME_COLOR 타입별 일반화된 "다음 필요 카드 목록" 반환.
- `CashInContext`/`CashInContextDto`에 `opponentHoldings` 추가 → 환금 단계에서도 상대 패를 반영.
- `S5BotStrategy` 도우미 픽 재조정: 보류 세트로 손패가 자주 한도를 넘는 점을 반영해 TUSKER·VIPER·CROC_BROTHERS·JUNK_DEALER 가중치 상향.

#### 상대 holdings 견제 — 컨텍스트 확장 (S4, 옛 §4.2)

> 사용자 원문: *"꾀부리기 견제 조건은 많지만, 가장 중요한 건 상대가 같은 색의 연속된 숫자를 모으지 못하게 세트를 쪼개서 분배하는 것(또는 뒷면으로 숨겨서 이용)."*

- `SplitContext` / `ChoiceContext` 에 `opponentHoldings` 필드 추가, `Game.teamHoldings(team)` 헬퍼로 주입.
- `S4BotStrategy.scoreSplit`: 상대 묶음 가치를 `standaloneValue + denyWeight × oppSynergy/100` 로 강화 → 상대 패에 시너지가 큰 묶음을 줄 경우 점수가 강하게 깎여, 자동으로 쪼개거나 뒷면으로 숨기는 분할이 선택됨.
- `S4BotStrategy.scoreChoice`: 안 가져간 묶음의 `oppSynergy` 가 크면 추가 페널티 → 상대 세트 완성 차단.
- 종반 페이드 없음 — holdings 견제는 `tighten` 곱 없이 항상 유지(사용자 직관 반영). S5도 동일.

### 측정 기록 (대표본)

- **S2 vs S1, 500판**: S2 53.0%(265/235), 평균 코인 29.13 / 28.69, 무효 환금 0.
- **S3 vs S2, 600판**: S3 54.3%(326/274), 평균 코인 거의 동률, 무효 0, z≈2.1(p≈0.02).
- **S3 self-play 스윕**: DEFAULT vs DEFAULT = 50.0%(노이즈 바닥), 어떤 변형도 DEFAULT 무회귀 → 파라미터 축 수확 체감 확인. `maximinWeight` 만 ON 이 중립~+.
- **S4 vs S3, 100판(seed 1~50, 선후공 교대)**: **S4 79.0%(79/100)**, 평균 코인 S4=31.10 / S3=25.84(+5.26), 무효 환금 0, 평균 라운드 9.42.
- **S5 vs S4, 100판(seed 1~50, 선후공 교대)**: **S5 56.0%(56/100)**, 평균 코인 S5=28.25 / S4=27.51(+0.74), 무효 환금 0, 평균 라운드 8.74.
- 해석: 파라미터 미세조정(S1→S2→S3) 단계는 3~4%p 개선에 머물렀고, **전략 축(환금 보류+상대 견제)을 바꾼 S4는 +29%p 수준의 도약**이었다. S5는 보류 판단 정밀화(이진→연속, 상대 손패 반영)로 +6%p 추가. 신호 방향은 일관되게 양수이고 무효 환금 0 이 유지되나, S5 우위는 100판 기준 통계적 유의 경계에 가까워 대표본 재측정을 권장한다(환금 동시진행 비결정성으로 소표본 노이즈가 있음).

---

## 4. 우선 작업 (로드맵)

> **S6 + 플레이 로그 완성(§4.6). 남은 순서: 사람 vs S6 로그 분석 → 4.4 → 4.5.** 4.1·4.1-S5·4.2 는 §3 으로 옮겼고, §4.3(측정 축 교체)은 플레이 로그 출력으로 실현했다. 이제 **사용자가 직접 S6 과 붙은 로그를 분석**해 다음 튜닝 방향을 잡는다.

### 4.6 [방금 완료] S6 — 사람 상대 약점 교정 + 플레이 로그

> 사용자 진단: *S5 는 봇끼리는 이기지만 사람은 못 이긴다.* 코드로 뿌리 셋을 확인하고 교정했다. 봇끼리 측정은 더 이상 강함의 척도가 아니므로(이 항이 §4.3 의 실현), 검증은 **사람 vs 봇 로그 분석**으로 옮긴다.

- **① 분할 호구 + ② 선택 오판(같은 뿌리)** — S5 는 분할 시 `chooserTakesA = visA > visB`(공개 코인 최대)로 상대 선택을 단순 가정했고, maximin 안전장치는 `maximinWeight × tighten/100` 라 종반에만 켜졌다. S6 `predictedChooserValue`(즉시 코인 + 상대 손패 시너지 + 뒷면 기대값)로 **현실적 선택자 모델**을 쓰고, `BASE_MAXIMIN_WEIGHT(=50)` 으로 maximin 을 **상시** 켠다. → 무방비 분할이 중반에도 줄고, 상대 색연속을 완성시키는 묶음을 순진하게 내주지 않는다.
- **③ 저주 떠넘김(뒷면 숨김만)** — **보이는 저주는 생각하는 상대에게 못 넘긴다**(그냥 다른 묶음을 집음). 그래서 초기 `curseRoutingScore` 의 "보이는 저주를 상대 묶음에 몰아넣기" 라우팅은 무의미할뿐더러 무방비 저주 더미를 만들어 바보같아 보여 **제거**했다(사용자 피드백, 2026-06-15). 남긴 것은 `hiddenCurseDumpScore` — 뒷면 카드가 저주이고 선택자가 가져갈 것으로 예측된 묶음이면 보너스(`CURSE_HIDDEN_DUMP`). 보이는 저주를 미끼로 쓰는 것은 기존 `lure` 가 자연히 처리한다(좋은 카드를 뒷면에 숨겨 저주 보이는 내 묶음을 상대가 피하게 함). 대박 보물 보호는 현실적 선택자 모델 + 뒷면 탐색에서 창발한다.
- **환금 도우미 활용 + 다중 세트 보류** — 드래프트는 게임 준비 시점이라 손패가 없어 손패 반영이 구조적으로 불가능. 실효 레버인 환금 단계 활용을 `CashInPlanOptimizer.planWithHoldS6`(S5 와 본문 공유 + `s6` 플래그)로 강화: ㉠ JUNK_DEALER 와일드 회수 가치를 손패 빌드 잠재력에 비례(`junkDealerScore`), ㉡ **보류를 단일→다중 세트로 확장**(`pickSetsToHoldS6`) — S5 는 한 세트만 보류해 작은 세트를 자잘하게 냈지만, S6 는 성장 기대값(`HOLD_GROWTH_THRESHOLD`)이 큰 세트를 한도가 허락하는 한 여러 개 보류해 크게 키운다(사용자 진단: "봇이 작게 자주 낸다"). 선택(분배) 로직은 S5 와 동일.
- **플레이 로그(`PlayLogWriter`)** — 오프라인 게임을 `logs/play-*.log` 에 사람 가독 + 결정시점 스냅샷(분할자 손패·양 팀 보관·코인·제시 묶음·분배 결과·환금 세트)으로 기록. `GameBoardController.startGame` 에서 `FanOutGameListener` 로 끼우고 기본 ON(`-Dpayday.playlog=false` 로 끔). 봇 내부 점수는 안 남김 — 판단 근거는 S6 소스로 재구성.
- **검증:** `S5vsS6SeedReport`(회귀 안전망: ~80%, 무효 환금 0) + `playLogCapturesFullGame`(로그 형식 회귀). 봇끼리 승률은 **사람 상대 강함의 증명이 아니다** — 사용자 로그 분석이 진짜 척도.
- **1차 플레이 분석(2026-06-15, user 31:22 승)** — 저주 더미는 패인이 아니었고(전부 숫자 매칭 무료 처분), 진짜 패인은 **사람이 더 큰 세트(연속 5장 10코인+4장 6코인)를 짓는데 봇은 2~5코인 작은 세트만 냈다.** → 다중 세트 보류로 1차 교정. 다음 로그에서 효과 확인 필요.

### 4.3 [실현됨 → §4.6] 측정 축 교체 — 정책 대결 + 사람 평가

플레이 로그 출력으로 실현했다. 봇 vs 봇 승률은 **회귀 안전망**(무효 환금 0 검증)으로만 남기고, "강함"의 척도를 바꾼다:

- **정성 평가:** 사용자가 직접 S6 과 붙은 `logs/play-*.log` 를 분석해 "쪼잔한 즉시 환금/무방비 분할"이 사라졌는지, 저주 떠넘김·대박 보호가 일어나는지 확인.
- **보조 지표 로깅:** 평균 환금 세트 크기, 게임당 환금 횟수, 상대 대형 세트 허용 횟수 — 로그에서 집계 가능. 향후 자동 집계는 선택적 과제.
- **4.1·4.2 분리 측정:** 보류만 ON(4.1-only) vs 견제만 ON(4.2-only) 변형으로 기여도를 분리하면 향후 튜닝 방향을 잡는 데 유용하다(선택적 과제).

### 4.4 [다음 우선] 난이도별 봇

`BotKind` 에 `EASY`/`NORMAL`/`HARD` 추가. EASY = 강한 결정에 확률적 무작위·지연을 섞는 **래퍼 전략**(일부러 약하게 = 재미). NORMAL = S3, HARD = S4. S4 완성으로 선행 조건 충족 — 이제 손댈 수 있다.

### 4.5 [장기] 매우 어려움

상대 공개 카드 추적의 누적(버림 더미·역할별 장기 전략), 시뮬레이션 기반 선택(상대 응답 분포를 가정한 lookahead). 4.1·4.2 의 컨텍스트가 선행되어야 하고 범위가 가장 크다.

---

## 5. 설계 원칙 (유지)

- 새 전략은 `BotStrategy` 인터페이스를 유지한다.
- 게임 엔진 규칙 로직과 봇 평가 로직을 분리한다(규칙 판정은 `SetEvaluator`/`CashInEvaluator`).
- 봇 전용 컨텍스트는 **직렬화 경로 밖**에 둔다 — 네트워크/UI 미러(DTO)를 오염시키지 않는다(§1·§4.2).
- 봇에 넘기는 정보는 **규칙상 공개된 것만**(보관 카드 O, 상대 도우미·손패 X).
- 각 액션 생성 전에 검증해 무효 행동을 내지 않는다(현재 0건 — 회귀 시 테스트가 잡는다).
- 성능 비교 기준선으로 `S1BotStrategy` 를 삭제하지 않는다. 새 실험은 별도 클래스(S4…)로 추가해 A/B 한다.
- 전략이 너무 완벽하면 재미가 떨어지므로 난이도별 변형을 둔다(§4.4).
