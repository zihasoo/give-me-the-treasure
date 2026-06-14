package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * {@link S2BotStrategy} 의 후속 전략(S3). 확장된 컨텍스트(보유 카드 + 양 팀 코인 + 승리 목표)를
 * 받아 분할·선택·환금을 판단한다. 중반엔 순진한 선택자(공개 코인 최대) 기준 <b>기대값</b>으로 평가하고,
 * 종반(긴박도 켜짐)에만 견제하는 상대(=사람)를 가정한 견고성 항을 얹는다. 가중치는 {@link S3Tuning} 으로
 * 묶여 자기복제 A/B 로 튜닝했다(기본값은 사람 상대 견고성을 위해 종반 maximin·견제를 켠 상태).
 *
 * <ul>
 *   <li><b>시너지</b>: 묶음을 고립 평가하지 않고 {@code potentialScore(holdings∪bundle) − potentialScore(holdings)}
 *       로 기존 패와의 한계 기여를 본다(코인은 그대로, 잠재력은 한 번만 — 이중 계산 없음).</li>
 *   <li><b>긴박도({@code tighten})</b>: 양 팀 중 승리에 가까운 쪽 기준, <b>마지막 {@code endzonePct}% 구간에서만</b>
 *       0→100 상승. 종반에 미래 잠재력·블러핑을 줄이고(코인이 전부), 분할 keep 을 최악보장(maximin)으로 끌고,
 *       선택에서 상대 몫을 깎는다(견제) — 중반엔 0이라 S2 처럼 논다.</li>
 *   <li><b>승리 확정 분할</b>: 두 묶음 모두 내가 가지면 이번 라운드 승리({@code bestCashCoin(holdings∪b) ≥ myNeed})면
 *       선택자가 무엇을 집든 이긴다 — 어떤 선택자 상대로도 손해 없는 압도적 보너스(maximin 의 극단 케이스).</li>
 *   <li><b>상대 임박 대응</b>: 환금은 상대도 승리 임박이면 보존·잠재력을 버리고 즉시 환금
 *       ({@link CashInPlanOptimizer#plan(CashInContext, int)}).</li>
 * </ul>
 */
public final class S3BotStrategy implements BotStrategy {

    /**
     * S3 의 스윕 가능한 가중치. 기본값({@link #DEFAULT})이 현재 측정된 S3 동작이다.
     * 튜닝은 <b>자기복제 A/B</b>(파라미터만 바꾼 S3끼리 대결)로 한다 — 순진한 S2의 약점에
     * 과적합하지 않고, 견제·블러핑 읽기를 하는 강한 상대(사람의 프록시)에게 통하는지를 보기 위함이다.
     *
     * @param endzonePct    종반 구간 폭(%): 누군가 {@code (100 − endzonePct)}% 도달 시점부터 긴박도가 켜진다.
     * @param lureWeight    미끼 유도력(블러핑) 가중치. 종반엔 자동 페이드.
     * @param maximinWeight 종반에 분할 keep 가치를 기대값→최악보장(maximin)으로 끄는 정도(0~100).
     *                      적대적(견제) 상대 대비 견고성. 0이면 순수 기대값(순진한 선택자 가정).
     * @param denyWeight    종반에 선택 시 상대에게 넘기는 즉시 코인을 깎는 강도(0~200, 100=코인 전액).
     */
    public record S3Tuning(int endzonePct, int lureWeight, int maximinWeight, int denyWeight) {
        // maximinWeight=100: 종반 견제(=사람이 하는 행동) 대비 견고성. 자기복제 스윕에서 실제로 견제하는
        // 상대에게도 비용이 없었고(≈중립~+1%), 종반에만 켜져 중반을 건드리지 않는다 — 사람 상대를 노린 선택.
        public static final S3Tuning DEFAULT = new S3Tuning(30, 40, 100, 100);
    }

    /** 승리 확정 분할/즉시 승리 선택에 주는 지배적 보너스(다른 모든 항을 압도). */
    private static final long WIN_SECURE = 1_000_000_000L;
    /** 선택 시 뒷면(미지) 카드 1장의 기대 가치 가산(S1/S2 와 동일 스케일). */
    private static final int FACEDOWN_BONUS = 20;

    private final S3Tuning tuning;

    public S3BotStrategy() {
        this(S3Tuning.DEFAULT);
    }

    public S3BotStrategy(S3Tuning tuning) {
        this.tuning = tuning;
    }

    @Override
    public String displayName() {
        return tuning.equals(S3Tuning.DEFAULT) ? "S3" : "S3" + tuning;
    }

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        List<Card> hand = context.hand();
        List<Card> holdings = context.holdings();
        int myNeed = need(context.winningCoins(), context.myCoins());
        int tighten = tighten(context.myCoins(), context.opponentCoins(), context.winningCoins());

        SplitDecision best = null;
        long bestScore = Long.MIN_VALUE;
        int n = hand.size();
        for (int mask = 1; mask < (1 << n) - 1; mask++) {
            int count = Integer.bitCount(mask);
            if (count != 1 && count != 2) {
                continue;
            }
            List<Card> bundleA = new ArrayList<>();
            List<Card> bundleB = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    bundleA.add(hand.get(i));
                } else {
                    bundleB.add(hand.get(i));
                }
            }
            for (Card faceDown : hand) {
                long score = scoreSplit(bundleA, bundleB, faceDown, holdings, myNeed, tighten);
                if (score > bestScore) {
                    bestScore = score;
                    best = new SplitDecision(bundleA, bundleB, faceDown);
                }
            }
        }
        return best;
    }

    private long scoreSplit(List<Card> bundleA, List<Card> bundleB, Card faceDown,
            List<Card> holdings, int myNeed, int tighten) {
        // 선택자(상대) 예측: 공개 coinValue 최대 쪽을 가져간다(대부분 봇의 실제 방식).
        int visA = BotCardEvaluator.bestCashCoin(without(bundleA, faceDown));
        int visB = BotCardEvaluator.bestCashCoin(without(bundleB, faceDown));
        boolean chooserTakesA = visA > visB || (visA == visB && bundleA.size() >= bundleB.size());

        // 내가 가질 묶음은 holdings 와의 시너지로, 상대 묶음은 단독으로 평가한다(상대 패는 모르므로).
        int valueA = myKeepValue(holdings, bundleA, tighten);
        int valueB = myKeepValue(holdings, bundleB, tighten);
        // 기본은 순진한 선택자가 내게 남길 묶음의 기대값. maximinWeight>0 이면 종반에 한해
        // 최악보장(적대적 선택자가 좋은 쪽을 가져가 내가 나쁜 쪽을 받는다)으로 끌어, 견제하는 상대 대비 견고해진다.
        int predMine = chooserTakesA ? valueB : valueA;
        int worstMine = Math.min(valueA, valueB);
        int mmw = tuning.maximinWeight() * tighten / 100; // 종반에만 활성(0~maximinWeight)
        int keep = (int) ((long) predMine * (100 - mmw) / 100 + (long) worstMine * mmw / 100);
        int theirs = standaloneValue(chooserTakesA ? bundleA : bundleB, tighten);

        // 미끼 유도력(좋은 카드를 내 쪽에 뒷면으로 숨겨 상대를 약한 묶음으로 유도) — 종반엔 자동 소거.
        int visTheirs = chooserTakesA ? visA : visB;
        int visMine = chooserTakesA ? visB : visA;
        int lure = visTheirs - visMine;
        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2;

        long score = 0L;
        // 승리 확정 분할: 양쪽 모두 내가 가지면 승리 → 선택자가 못 막는다.
        if (myNeed > 0 && secures(holdings, bundleA, myNeed) && secures(holdings, bundleB, myNeed)) {
            score += WIN_SECURE;
        }
        score += (keep - theirs) * 10_000L + keep * 10L;
        score += (long) lure * tuning.lureWeight() * (100 - tighten) / 100;
        score += balanced ? 1L : 0L;
        return score;
    }

    @Override
    public int decideChoice(ChoiceContext context) {
        ChoiceView view = context.view();
        List<Card> holdings = context.holdings();
        int myNeed = need(context.winningCoins(), context.myCoins());
        int tighten = tighten(context.myCoins(), context.opponentCoins(), context.winningCoins());

        int best = 0;
        long bestScore = Long.MIN_VALUE;
        int bestSize = -1;
        int bundleCount = view.bundles().size();
        for (int i = 0; i < bundleCount; i++) {
            BundleView mine = view.bundle(i);
            BundleView other = view.bundle((i + 1) % bundleCount); // 1v1: 안 가져간 묶음은 상대(분할자) 몫
            long score = scoreChoice(mine, other, holdings, myNeed, tighten);
            if (score > bestScore || (score == bestScore && mine.size() > bestSize)) {
                best = i;
                bestScore = score;
                bestSize = mine.size();
            }
        }
        return best;
    }

    private long scoreChoice(BundleView mine, BundleView other, List<Card> holdings, int myNeed, int tighten) {
        List<Card> mineVisible = mine.visibleCards();
        long score = myKeepValue(holdings, mineVisible, tighten);
        if (mine.hasFaceDown()) {
            score += FACEDOWN_BONUS; // 가져가면 뒷면 카드도 내 것 — 기대 가치 가산
        }
        // 즉시 승리: 이 묶음을 가져가면 이번 라운드에 이긴다(뒷면 제외 보수적 판정).
        if (secures(holdings, mineVisible, myNeed)) {
            score += WIN_SECURE;
        }
        // 종반 견제: 안 가져간 묶음은 상대 몫 → 상대가 바로 환금할 코인을 깎는다(긴박도·denyWeight 비례).
        int theirCoin = BotCardEvaluator.bestCashCoin(other.visibleCards()) * 100;
        score -= (long) theirCoin * tighten * tuning.denyWeight() / 10_000;
        return score;
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        return options.stream()
                .sorted(Comparator.comparingInt(this::helperDraftScore).reversed())
                .limit(chooseCount)
                .toList();
    }

    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        return CashInPlanOptimizer.plan(context, opponentCoins);
    }

    // --- 평가 보조 ---

    /** 내가 가질 묶음의 가치: 코인(전액) + holdings 와의 시너지(종반엔 잠재력 페이드). */
    private static int myKeepValue(List<Card> holdings, List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int synergy = synergy(holdings, bundle) * (100 - tighten) / 100;
        return coin + synergy;
    }

    /** 상대가 가질 묶음의 가치: 코인(전액) + 단독 잠재력(종반엔 페이드). 상대 패를 모르므로 holdings 없이. */
    private static int standaloneValue(List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int potential = BotCardEvaluator.potentialScore(bundle) * (100 - tighten) / 100;
        return coin + potential;
    }

    /** 묶음을 기존 패에 더했을 때 늘어나는 잠재력(한계 시너지). */
    private static int synergy(List<Card> holdings, List<Card> bundle) {
        List<Card> combined = new ArrayList<>(holdings);
        combined.addAll(bundle);
        return BotCardEvaluator.potentialScore(combined) - BotCardEvaluator.potentialScore(holdings);
    }

    /** 이 묶음을 가지면 기존 패와 합쳐 이번 라운드 승리에 필요한 코인을 만들 수 있는가. */
    private static boolean secures(List<Card> holdings, List<Card> bundle, int myNeed) {
        if (myNeed <= 0) {
            return false;
        }
        List<Card> combined = new ArrayList<>(holdings);
        combined.addAll(bundle);
        return BotCardEvaluator.bestCashCoin(combined) >= myNeed;
    }

    /** 승리까지 남은 코인(음수 없음). */
    private static int need(int winningCoins, int coins) {
        return Math.max(0, winningCoins - coins);
    }

    /**
     * 긴박도 0~100: 양 팀 중 승리에 가까운 쪽 기준. <b>마지막 {@code endzonePct}% 구간에서만</b> 0→100 으로 오른다.
     * 그 전(중반까지)은 0 — 잠재력·블러핑을 온전히 살리고 견제도 끈다. 종반 견제·즉시 코인 우선은
     * 실제로 게임이 끝나갈 때만 켜져, 중반에 보수성이 새어 들어 손해 보는 걸 막는다.
     */
    private int tighten(int myCoins, int opponentCoins, int winningCoins) {
        if (winningCoins <= 0) {
            return 0;
        }
        int closest = Math.min(need(winningCoins, myCoins), need(winningCoins, opponentCoins));
        int endzone = winningCoins * tuning.endzonePct() / 100; // 누군가 (100−endzonePct)% 도달 시점부터
        if (endzone <= 0 || closest >= endzone) {
            return 0;
        }
        return clamp((endzone - closest) * 100 / endzone, 0, 100);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private int helperDraftScore(HelperCard helper) {
        return switch (helper.kind()) {
            case LUCKY -> 105;
            case ALPHA -> 100;
            case LEO, CUCKOO -> 88;
            case VIPER -> 82;
            case TUSKER -> 72;
            case CROC_BROTHERS -> 64;
            case DOUG -> 56;
            case JUNK_DEALER -> 48;
        };
    }

    private List<Card> without(List<Card> cards, Card excluded) {
        List<Card> copy = new ArrayList<>(cards);
        copy.remove(excluded);
        return copy;
    }
}
