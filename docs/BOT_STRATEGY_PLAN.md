# 봇 전략 현황과 로드맵

봇 의사결정 구조의 현재 상태와 앞으로 할 일을 정리한 살아있는 문서다.
**이 문서만 보고 다음 작업을 코드로 옮길 수 있도록** 진단·구현 위치·알고리즘·검증 기준을 함께 적는다.

---

## 0. 한 줄 요약

**S8이 현재 최신·기본 봇이다.** Heuristic → S1 → ... → S7 → S8 순으로 진화했다. S8은 S7의 한 라운드
정교함을 계승하면서 로드맵 4.1~4.6의 **S8급 구조 개선**을 한 번에 얹었다: ①공개 정보 메모리/카드 카운팅
(`PublicCardTracker`), ②상대 선택 확률 모델(`OpponentModel`, 확률 prior), ③실제 환금 결과 기반 종반 평가
(`CashInProjection`·`projectedCashCoin` — ALPHA 즉승·LUCKY·JOE 반영), ④2v2 팀 분배(`TeamDistributionOptimizer`),
⑤도우미 드래프트 상황화, ⑥후보 점수 breakdown 로그(`ScoreBreakdown`, `-Dbot.debugScores=true`).

봇은 여전히 **무상태 순수 전략**이다 — "메모리"는 매 결정마다 컨텍스트의 공개 정보(내 보관·상대 보관·버림
더미)로 재구성한다(이벤트 구독 없음).

S7 회귀 체온계(자가대전 400판): **S8 53.0% vs S7**, 평균 코인 28.54 vs 27.82, 무효 환금 0건(2026-06-17).
강함의 주 지표는 여전히 사람 vs 봇 로그다. 4.3 상대 모델의 **관찰/학습**과 4.8 lookahead는 후속.

---

## 1. 현재 구조

봇은 `BotStrategy` 인터페이스로 모든 의사결정을 위임한다(전략 패턴).

- `decideSplit(SplitContext)`
- `decideChoice(ChoiceContext)`
- `decideHelpers(List<HelperCard>, chooseCount)`
- `planCashIn(CashInContext, opponentCoins)`
- `decideTeamDistribution(acquired, memberHoldings)`

구현체와 유틸 (`com.oop.payday.bot`):

| 클래스 | 역할 |
|---|---|
| `S8BotStrategy` | 현재 최신·기본 봇. S7 평가를 계승하고 상대 확률 모델·카드 카운팅·실현 코인 종반 평가·2v2 분배·도우미 상황화·점수 breakdown 로그를 얹음(무상태). |
| `S7BotStrategy` | 직전 세대. `BotKind` 비노출, `HeadlessBotGameTest` 회귀 baseline으로만 사용. |
| `PublicCardTracker` | 공개 정보(내·상대 보관, 버림 더미)로 재구성하는 무상태 카드 위치/카운팅. 카드 생존(`isLive`)·와일드 버림(`wildInDiscard`)·세트 성장 턴(`expectedTurnsToGrow`). |
| `OpponentModel` | 분할 평가용 상대 선택 확률 prior(`pTakesA`). 선호 가중치 벡터로 softmax. `DEFAULT`는 S7 예측과 동치. |
| `CashInProjection` / `CashInPlanOptimizer.project` | 환금 계획의 예상 코인/즉승/잔여 반환. 종반 판단·디버그 로그용. |
| `BotCardEvaluator` | 카드 잠재력(`potentialScore`)·세트 코인(`bestCashCoin`)·버릴 카드(`chooseDiscard`)에 더해, 도우미/리더까지 본 실현 코인(`projectedCashCoin`, ALPHA 즉승=`ALPHA_WIN_COIN`). |
| `TeamDistributionOptimizer` | 2v2 분배: 세트 완성 카드 몰아주기 + 저주 라우팅(잠재력 증분 최대화 + 균형 페널티). |
| `ScoreBreakdown` | 후보 점수 항목별 내역. `-Dbot.debugScores=true`면 `S8`이 `logs/bot-scores.log`에 후보표 기록. |
| `CashInPlanOptimizer` | 환금 조합 탐색, 다중 세트 보류, 도우미 사용, 강제 처분 계획. 세대별 정책은 `Tuning`(S7/S8)으로 갈아끼움. |
| `BotKind` | 대기실 봇 슬롯 선택지. `EASY`/`NORMAL`/`HARD`를 쉬움/중간/어려움으로 노출한다. |
| `PlayLogWriter` | 오프라인 게임 진행을 `logs/play-*.log`에 기록. 사람 vs 봇 사후 분석용. |

**컨텍스트 분리 원칙:** 봇 전용 정보(`opponentCoins`)는 `beginCashIn`의 별도 인자로 흐른다. 규칙상 공개 정보(`opponentHoldings`)는 `CashInContext`/`CashInContextDto`에 포함해 네트워크로 전달한다. S8용으로 추가한 `discardPile`·자기 `helpers`·자기 `officer`는 봇 전용 `SplitContext`/`ChoiceContext`(네트워크 미러 없음)에만 넣어 DTO를 오염시키지 않는다. 도우미 드래프트 상황화를 위해 `decideHelpers`는 `HelperDraftContext`(인원수·한도·리더·승리 코인)를 받는다(비봇 플레이어는 무시).

**현재 한계:** S8은 분할/선택/환금과 2v2 분배까지 다루지만, 상대 모델은 아직 prior 고정(관찰/학습 미배선)이고, 환금 단계 자체는 검증된 S7 경로(`Tuning.S8`=S7 수치)를 공유한다. lookahead(4.8)는 미구현이다.

---

## 2. 진화 기록

각 세대의 핵심 변화와 당시 봇끼리 대전 결과를 남긴다. 구현체가 제거된 세대도 설계 맥락 보존을 위해 요약한다.

| 버전 | 핵심 추가 | 이전 대비 승률 |
|---|---|---|
| Heuristic | 규칙 기반. 세트 코인 가치만 보고 단순 선택. 비교 하한선. | - |
| S1 | 점수 기반 기준선. 환금 조합 최적화 + 카드 잠재력 평가. | - |
| S2 | S1 + 분할 블러핑 명시화(`LURE_WEIGHT`) + 자기 팀 임박 환금. | S1 대비 53% |
| S3 | S2 + 상황 컨텍스트(보유 카드·양 팀 코인). 시너지·적응형 선택자 모델·승리 확정 분할. | S2 대비 54% |
| S4 | S3 + 환금 보류(`planWithHold`) + 상대 보관 카드 견제(`oppSynergy`). 전략 축 변경. | S3 대비 **79%** |
| S5 | S4 + 보류 판단 정밀화(이진→연속 기대값, 상대 손패 반영). `CashInContext`에 `opponentHoldings` 추가. | S4 대비 56% |
| S6 | S5 + 현실적 선택자 모델·상시 maximin·다중 세트 보류·뒷면 저주 떠넘김 + `PlayLogWriter`. | S5 대비 **72%** |
| S7 | S6 + 적대적 뒷면/와일드 평가, 저주 부채, 같은색 연속 보류 강화, 종반 실현 코인 마진. 사람 vs S6/S7 로그 기반 교정. | S6 대비 58.2% |
| S8 | S7 + 카드 카운팅(`PublicCardTracker`)·상대 확률 모델(`OpponentModel`)·실현 코인 종반 평가(`projectedCashCoin`, ALPHA 즉승)·2v2 분배·도우미 상황화·점수 breakdown 로그. 로드맵 4.1~4.6. | S7 대비 53.0% |

**해석:** S1→S3의 파라미터 조정은 3~4%p 개선에 머물렀고, S4처럼 전략 축을 바꾼 변경이 크게 뛰었다. S6는 봇끼리 강했지만 사람 상대 2판(2026-06-15 user 31:22, 2026-06-16 user 32:20)에서 졌다. 그래서 S7부터는 **사람 vs 봇 로그 분석을 주 지표**로 삼는다.

봇끼리 대량전은 폐기할 지표가 아니라 **회귀 체온계**로만 쓴다. 새 변경이 명백히 망가졌는지 확인하는 자동 안전망으로 유지하고, 강함 평가는 사람 로그와 특정 미스플레이 재현 테스트가 맡는다. S6 구현체를 제거한 뒤로는 `HeadlessBotGameTest.s7SelfPlaySeedReport`(S7 자가대전, `-DbotSeedCount=250`, 선후공 2판 = 500판)로 크래시·무효 환금만 감시한다. 제거 직전 마지막 S7 vs S6 대표본은 S7 291승 / S6 209승, 승률 58.2%, 평균 코인 S7 28.81 vs S6 27.30, 무효 환금 0건이었다(2026-06-17 실행).

---

## 3. S6/S7 핵심 교훈

과거 로그별 장문 분석은 코드와 테스트에 반영된 내용만 남기고, 앞으로도 유지해야 할 교훈 위주로 정리한다.

### 3.1 S6에서 얻은 것

- `predictedChooserValue`: 공개 코인만 보던 선택자 예측을 즉시 코인 + 상대 보관 시너지 + 뒷면 기대값으로 확장했다.
- 상시 maximin: 종반이 아니어도 최악의 경우를 일부 반영해 무방비 분할을 줄였다.
- 뒷면 저주 떠넘김: 보이는 저주는 상대가 피하므로, 뒷면 저주일 때만 떠넘김 보너스를 준다.
- 다중 세트 보류: 작은 세트를 즉시 환금하지 않고 성장 기대값이 큰 세트를 여러 개 보류한다.
- `PlayLogWriter`: 사람 vs 봇 분석의 기반을 만들었다.

### 3.2 S7에서 고친 것

- **와일드 헌납 방지:** `hiddenEv`로 얇은 뒷면 묶음을 의심하고, `WILD_GRAB`으로 보이는/숨은 와일드의 확보·견제 가치를 크게 본다.
- **분할자 와일드 보상 분할:** 와일드는 숨겨도 숙련자가 읽을 수 있으므로, `predictedChooserValue`는 뒷면 와일드도 선택자가 가져간다고 예측한다. 봇은 헛된 숨김보다 와일드를 내주는 대신 남는 묶음을 알차게 짜야 한다.
- **이유 없는 1:4 방지:** 선택자 예측은 뒷면 의심만이 아니라 카드 물량(`CARD_MATERIAL`)을 본다. 4장 묶음은 대체로 1장+뒷면보다 매력적이다.
- **저주 부채:** `CURSE_LIABILITY`로 저주를 음의 가치로 보고, S7 환금 튜닝에서 저주를 보류 공간 계산에서 제외한다.
- **저주 처분 타이밍:** 무료 처분 보너스는 한도 초과일 때만 켜고, 바이퍼는 한도 초과 구제용으로 아낀다.
- **큰 세트 양성:** 같은색 연속 보류 임계를 낮추고(`0.5`), 성장 가중을 더하며, 보류 방이 부족하면 잡카드를 비운다.
- **종반 선택 마진:** 30코인을 먼저 찍어도 같은 라운드에 상대가 더 많이 벌면 진다. 임계 통과 묶음끼리는 `bestCashCoin(내 보관+묶음) - bestCashCoin(상대 보관+상대 묶음)` 마진으로 고른다.

### 3.3 S7 취약점 → S8 처리 현황

- 종반 실현 마진의 `bestCashCoin` 근사(도우미·리더·알파 즉승 누락) → **S8에서 처리**(`projectedCashCoin`, §4.4). 단 VIPER/CHUCK·상대측은 여전히 근사.
- 2v2 `decideTeamDistribution` 균형 분배의 약함 → **S8에서 처리**(`TeamDistributionOptimizer`, §4.5). 단 멤버 도우미 라우팅은 미완.
- 사람의 다라운드 장기 빌드를 누적 추적하는 모델 없음 → **여전히 없음.** S8 카드 카운팅은 무상태 순간 재구성이라 "흐름 기억"은 아님(§4.2/§4.3 남은 일).
- 봇이 **분할자로서 와일드**를 쥔 보상 분할은 로그 검증 부족 → **여전히 미검증**(단위 테스트로 고정 필요, §4.1 남은 일).
- 한도 5에서 loose가 전부 페어/런 재료면 4·5장 보류가 막힐 수 있음 → **여전히 가능**(환금 옵티마이저 S7 경로 공유, §4.2 남은 일).

---

## 4. 로드맵 — 구현 상태

> 주 지표는 사람 vs 봇 로그 분석이다. 봇 vs 봇 대량전은 회귀 안전망, 특정 미스플레이 테스트는 고정 안전핀으로 쓴다.

**범례:** ✅ 완료 · 🟡 부분(핵심만 됨) · ❌ 미구현. 각 항목 아래에 *한 일 / 남은 일*을 적는다.
**다음 봇이 손댈 최우선은 §4.3 상대 모델 학습**(관찰 배선) → 그다음 §4.2 환금 연결·§4.5 도우미 라우팅 완성 → 그 후 §4.7/§4.8.

| 항목 | 상태 | 한 줄 |
|---|---|---|
| 4.1 점수 breakdown 로그/테스트 | ✅ | `ScoreBreakdown` + `logs/bot-scores.log`(`-Dbot.debugScores`) + 단위 테스트 |
| 4.2 공개 정보 메모리/카드 카운팅 | 🟡 | `PublicCardTracker`는 분할/선택 성장 보너스에만 연결. 환금 옵티마이저·상대 견제 미연결 |
| 4.3 상대 선택 확률 모델 | 🟡 | `OpponentModel` **prior만**. 관찰/학습 배선 없음 → **다음 최우선** |
| 4.4 실현 코인 종반 평가 | ✅(근사) | `projectedCashCoin`·`CashInProjection`. VIPER/CHUCK·상대측은 근사 |
| 4.5 2v2 팀 분배 | 🟡 | `TeamDistributionOptimizer`는 holdings 기반. 멤버 도우미/officer 미반영 |
| 4.6 도우미 드래프트 상황화 | 🟡 | 얕은 보정만. `CROC` 공개 도우미 시너지 미반영 |
| 4.7 난이도별 봇 | ✅ | S8 기반 EASY/NORMAL/HARD 노출 |
| 4.8 lookahead/시뮬레이션 | ❌ | 미착수 |

### 4.1 [✅ 완료 · S8] 분석 가능한 로그/테스트 인프라 강화

**한 일:** `ScoreBreakdown` record + `S8`의 `-Dbot.debugScores=true` 시 `logs/bot-scores.log` 기록(SPLIT/CHOICE 후보별 `coin/synergy/hidden/wild/curse/deny/margin/total`, `[*]`=선택, CASH `coinsGained/instantWin`). 단위 테스트: `S8BotStrategyTest`(ALPHA 즉승·와일드 확보·S7 교정 계승), `CashInProjectionTest`.

**남은 일:** 로드맵이 지목한 미스플레이 중 단위 테스트로 아직 고정 안 된 것 — `001705 R9`형 종반 실현 마진 선택, 한도 5에서 5장 런 보류/완성, 바이퍼 한도여유 낭비 방지, 저주 무료 처분 한도 초과 한정. (현재는 사람 로그를 봐야 확인 가능 → 픽스처로 고정 필요.)

### 4.2 [🟡 부분 · S8] 공개 정보 메모리/카드 카운팅

**한 일:** `PublicCardTracker`(무상태, 매 결정마다 컨텍스트로 재구성) — `locationOf`(MINE/OPP/DISCARD/UNKNOWN)·`isLive`·`wildInDiscard`·`unknownTreasureCount`·`expectedTurnsToGrow`. S8 `myKeepValue`에 '살아 있는 성장' 보너스로 연결(성장 카드가 죽었으면 0). 검증: `PublicCardTrackerTest`.

**남은 일 (이 항목의 완료 기준이 아직 미충족):**
- **환금 옵티마이저 연결** — `CashInPlanOptimizer.expectedStepTurns`(비공개)는 아직 옛 총량 근사를 쓴다. S7 회귀 안정성 때문에 미변경. `Tuning.S8` 분기를 만들어 트래커 기반으로 교체해야 "정확한 카드 위치로 보류 판단" 기준 충족.
- **상대 완성직전 견제** — 트래커의 "상대가 완성 직전 + 그 카드가 살아 있음" 감지를 분할/선택 견제에 미연결(이중계산 우려로 보류). `wildInDiscard`도 옵티마이저의 `JUNK_DEALER` 판단(자체 discard 스캔)과 일원화 안 됨.

### 4.3 [🟡 부분 · S8] 상대 선택 확률 모델

**한 일:** `OpponentModel`(무상태 prior 벡터: 코인·물량·시너지·와일드·뒷면·저주 + 온도). `S8.scoreSplit`이 `chooserTakesA` boolean 대신 `P(takesA)` 기대값으로 계산(상대가 A를 가져가면 나는 B). `DEFAULT`는 S7 예측과 동치(온도→0). 검증: `OpponentModelTest`.

**남은 일 = 학습 배선 (다음 봇의 최우선 작업).** 지금은 prior 고정이라 "상대가 실제로 어떻게 선택해왔는지"를 전혀 반영 못 한다. 봇은 무상태라 상대의 선택을 한 번도 **관찰**하지 못하는 게 근본 한계다. 다음 4단계가 필요:

1. **관찰 배선 (가장 큰 작업).** `Game`은 단일 `GameListener`뿐이라 봇은 이벤트를 못 받는다. `BotStrategy`에 `observe(GameObservation)` 기본 no-op 훅을 추가하고, `distributePhase`에서 선택자가 고른 뒤 **상대 팀 봇**에게 `(제시된 두 묶음, 고른 index)`를 통지한다(`onDistributed`로 실제 카드가 공개되므로 룰상 합법). 추가 신호(선택): 상대 환금 패턴(큰 세트 보류/저주 즉시 처분), 도우미 사용.
2. **상태 있는 전략.** `S8`(또는 `S9`)이 **게임 단위** `OpponentModel`을 보유하고 관찰마다 갱신. 게임마다 리셋이 안전(세션 넘는 사람 식별·저장은 권장 안 함). → `BotPlayer`가 전략 인스턴스를 게임 내내 들고 있으므로 가능.
3. **갱신 규칙 (prior → posterior).** 관찰 `(bundleA,bundleB,chosen)`마다 feature로 `P(takesA)` 예측 후 실제와 비교해 가중치를 이동. 간단·견고: 온라인 로지스틱(퍼셉트론식) 또는 feature 빈도 집계. **콜드 스타트**: 관찰 적으면 `DEFAULT` prior로 정규화(증거를 샘플 수로 가중)해 초반 폭주 방지.
4. **검증.** 고정 성향 스크립트 상대로 모델이 그 성향에 **수렴**하는지 단위 테스트 + 과거 `play-*.log` **리플레이**로 예측 log-loss 측정.

완료 기준: 뒷면을 잘 읽는 사람에겐 와일드 숨김을 덜 시도하고, 공개 코인에 약한 사람에겐 미끼 분할을 더 쓰는 등 **상대마다** 분할이 달라진다.

### 4.4 [✅ 완료(근사) · S8] 실제 환금 결과 기반 종반 평가

**한 일:** `BotCardEvaluator.projectedCashCoin`(세트 코인 + 도우미 CUCKOO/LEO +3·LUCKY +7 + 리더 JOE/WISE +1, ALPHA 즉승=`ALPHA_WIN_COIN`). `S8.scoreChoice` 종반 마진 입력을 `bestCashCoin`→이 실현 코인으로 교체, ALPHA 즉승 묶음은 코인 무관 최우선. `CashInPlanOptimizer.project`→`CashInProjection(actions, coinsGained, instantWin, remainingCards)` 추가. 검증: `S8BotStrategyTest`·`CashInProjectionTest`.

**남은 일 (근사 보강):** `CashInProjection.coinsGained`가 VIPER 코인·CHUCK(라운드 환금 횟수)를 빠뜨림. 종반 마진의 **상대 쪽**은 상대 도우미/officer가 비공개라 순수 `bestCashCoin` 근사(비대칭). 다중 환금·`scoreSplit` 종반 마진도 아직 단일 세트 근사. 더 정확히 하려면 상대 officer를 `ChoiceContext`에 넣고(공개 정보) `project`로 다중 환금까지 합산.

### 4.5 [🟡 부분 · S8] 2v2 팀 분배 전략

**한 일:** `TeamDistributionOptimizer.distribute` — 각 카드를 멤버 보유에 더한 `potentialScore` 증분이 큰 멤버에 배정(시너지 큰 카드부터, 과적 방지 균형 페널티). potentialScore가 세트 완성·저주 무료처분(같은 숫자)·숫자1 가점을 이미 반영해 단일 점수로 핵심 규칙 만족. `S8`이 `decideTeamDistribution` override. 검증: `TeamDistributionOptimizerTest`.

**남은 일:** 분배 입력(`decideTeamDistribution(acquired, memberHoldings)`)에 **멤버별 도우미/officer가 없어** 도우미 연계 라우팅이 안 됨 — 숫자1→`ALPHA` 보유자, 저주→`VIPER` 보유자, 같은색5→`LUCKY`, `FLANKY` 한도차 반영. 하려면 `decideTeamDistribution` 시그니처를 확장(멤버 도우미/officer 전달)해야 함(현재는 인터페이스 변경 최소화로 holdings 기반만).

### 4.6 [🟡 부분 · S8] 도우미 드래프트 상황화

**한 일:** `decideHelpers`에 `HelperDraftContext`(인원수·한도·리더·승리 코인) 추가(인터페이스·`Player` 계층 전체 시그니처 확장, 비봇은 무시). `S8.helperDraftScore`가 정적 순위에 상황 보정: 1v1 한도≤6에서 TUSKER/DOUG/VIPER↑, LUCKY↑, 2v2에서 ALPHA↑. S7은 정적 순위 유지(baseline).

**남은 일:** 보정이 얕다. `CROC_BROTHERS`의 공개된 사용 도우미 시너지 미반영(`HelperDraftContext`에 `visibleUsed` 추가 필요). 드래프트 가치를 실제 환금 기대치와 연계하는 정밀화도 미완.

### 4.7 [✅ 완료] 난이도별 봇

> `BotKind`는 `EASY`/`NORMAL`/`HARD`를 쉬움/중간/어려움으로 노출한다. 내부 기준선은 S8이다.

강한 봇을 먼저 만든 뒤, 약한 봇은 강한 결정 위에 노이즈를 얹어 만든다.

한 일:

- `BotKind`에 `EASY`/`NORMAL`/`HARD` 추가.
- `HARD`: 최신 전략 풀파워(S8).
- `NORMAL`: S8을 쓰되 상대 보관/버림 더미/도우미/간부 정보를 가려 메모리·상황화를 일부 비활성.
- `EASY`: NORMAL 기반에 단순 분할과 공개 코인만 보는 선택을 확률적으로 섞고, 2v2 분배·도우미 선택도 단순화.

완료 기준:

- 같은 코드 경로를 공유하면서도 체감 난이도가 구분된다.

### 4.8 [❌ 미구현] 시뮬레이션/lookahead

> **남은 일(미착수).** 4.3 학습·4.2 환금 연결이 성숙한 뒤에 의미가 있다. 그 전엔 착수하지 말 것.

상태 추적과 상대 모델이 생긴 뒤에야 의미가 있다.

작업:

- 공개 정보 기반으로 남은 덱 분포를 샘플링한다.
- 분할/선택/환금 한두 라운드 앞을 rollout한다.
- 상대 응답은 `OpponentModel`의 선택 확률을 사용한다.

완료 기준:

- 단기 점수상 손해지만 다음 라운드 대형 세트/상대 차단으로 이기는 수를 찾는다.
- 계산 시간이 플레이 템포를 해치지 않는다.

---

## 5. 검증 원칙

- 사람 vs 봇 로그가 강함의 주 지표다.
- 봇 vs 봇 승률은 회귀 체온계로만 쓴다. `HeadlessBotGameTest.s8VsS7SeedReport`(challenger S8 vs baseline S7)처럼 같은 seed 표본에서 선후공을 바꿔 돌리고, 새 전략이 S7 대비 터무니없이 무너지면 되돌아본다. `s8BotFinishesPracticeGameWithoutInvalidActions`로 무효 환금 0건을, `s7SelfPlaySeedReport`로 S7 baseline 자체를 감시한다. 실행: `gradlew integrationTest -DbotSeedCount=300`.
- 중요한 사람 로그 미스플레이는 단위 테스트로 고정한다.
- 무효 행동은 0건이어야 한다. `HeadlessBotGameTest`와 액션 검증으로 잡는다.
- 로그에는 최종 행동뿐 아니라 후보와 점수 근거를 남겨야 한다.

---

## 6. 설계 원칙

- 새 전략은 `BotStrategy` 인터페이스를 유지한다. 단, 드래프트/메모리 등 컨텍스트가 부족한 경우에는 새 context record 추가를 검토한다.
- 게임 엔진 규칙 로직과 봇 평가 로직을 분리한다(`SetEvaluator`/`CashInEvaluator`/`CashInPlanOptimizer`).
- 봇 전용 추론 상태는 네트워크/UI 미러 DTO를 오염시키지 않는다.
- 봇에 넘기는 정보는 규칙상 공개된 것만 사용한다. 상대 도우미·손패 같은 비공개 원본은 직접 참조하지 않고, 공개 행동 기반의 확률 추정만 허용한다.
- 강한 봇을 먼저 만들고, 난이도 하향은 래퍼/노이즈/기능 제한으로 만든다.
