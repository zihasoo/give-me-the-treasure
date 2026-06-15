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
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.helper.HelperCard;

/**
 * S5 기반에서 <b>사람 상대 약점</b> 세 가지를 고친 전략.
 *
 * <p>S5 는 봇끼리는 이기지만 사람에게 약하다. 코드로 확인한 뿌리는 셋이다.
 *
 * <ol>
 *   <li><b>① 분할 호구 + ② 선택 오판(같은 뿌리)</b> — S5 는 분할 시 "선택자(상대)는 공개 코인 큰 쪽을
 *       가져간다"고 단순 가정한다({@code chooserTakesA = visA > visB}). 사람은 뒷면·자기 손패 시너지를
 *       보고 다르게 고르므로 봇이 무방비하게 나눈다. S6 는 {@link #predictedChooserValue}로
 *       <b>현실적 선택자 모델</b>(즉시 코인 + 상대 손패와의 시너지 + 뒷면 기대값)을 쓰고, maximin
 *       안전장치를 종반에만이 아니라 {@link #BASE_MAXIMIN_WEIGHT}만큼 <b>상시</b> 켠다.</li>
 *   <li><b>③ 저주·고가치 보물의 의도적 플레이 부재</b> — S6 는 {@code potentialScore} 와 별개로
 *       <b>저주 라우팅</b>(상대 묶음으로 떠넘기고, 특히 뒷면으로 숨겨 보내는) 항을 명시한다.
 *       대박 보물 보호(keystone 을 내 쪽에 뒷면으로 숨겨 선택자가 내 묶음을 저평가하게 함)는
 *       현실적 선택자 모델 + 뒷면 탐색에서 자연히 창발한다.</li>
 *   <li><b>도우미 활용</b> — 드래프트는 게임 준비 시점이라 손패가 없어 손패 반영이 구조적으로 불가능하다.
 *       실효 레버인 <b>환금 단계 도우미 활용</b>을 {@link CashInPlanOptimizer#planWithHoldS6}로 강화한다
 *       (JUNK_DEALER 와일드 회수 가치를 손패 빌드 잠재력에 비례).</li>
 * </ol>
 *
 * <p>선택(분배) 로직과 보류 판단은 S5 와 동일하다 — ②는 분할 시 상대 선택 예측의 문제이지 봇 자신의
 * 선택 문제가 아니므로 {@link #decideChoice}는 손대지 않는다.
 */
public final class S6BotStrategy implements BotStrategy {

    private static final S3BotStrategy.S3Tuning TUNING = S3BotStrategy.S3Tuning.DEFAULT;

    private static final long WIN_SECURE = 1_000_000_000L;
    private static final int FACEDOWN_BONUS = 20;

    /**
     * 상시 maximin 안전장치(0~100). S5 는 {@code maximinWeight × tighten/100} 라 종반에만 켜졌다.
     * S6 는 이 값을 바닥으로 깔아 중반에도 무방비 분할을 줄인다(현실적 선택자 모델로 예측이 선명해진
     * 만큼만 적당히). 종반에는 {@code TUNING.maximinWeight()}(=100)까지 상승한다.
     */
    private static final int BASE_MAXIMIN_WEIGHT = 50;

    /** 뒷면으로 숨겨 상대 묶음에 저주를 떠넘길 때의 보너스(선택자가 못 알아챔). */
    private static final int CURSE_HIDDEN_DUMP = 9_000;

    @Override
    public String displayName() {
        return "S6";
    }

    // ─── 분할(꾀부리기) ───────────────────────────────────────────────────────────

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        List<Card> hand = context.hand();
        List<Card> holdings = context.holdings();
        List<Card> oppHoldings = context.opponentHoldings();
        int myNeed = need(context.winningCoins(), context.myCoins());
        int tighten = tighten(context.myCoins(), context.opponentCoins(), context.winningCoins());

        SplitDecision best = null;
        long bestScore = Long.MIN_VALUE;
        int n = hand.size();
        for (int mask = 1; mask < (1 << n) - 1; mask++) {
            int count = Integer.bitCount(mask);
            if (count != 1 && count != 2) continue;
            List<Card> bundleA = new ArrayList<>();
            List<Card> bundleB = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) bundleA.add(hand.get(i));
                else bundleB.add(hand.get(i));
            }
            for (Card faceDown : hand) {
                long score = scoreSplit(bundleA, bundleB, faceDown, holdings, oppHoldings, myNeed, tighten);
                if (score > bestScore) {
                    bestScore = score;
                    best = new SplitDecision(bundleA, bundleB, faceDown);
                }
            }
        }
        return best;
    }

    private long scoreSplit(List<Card> bundleA, List<Card> bundleB, Card faceDown,
            List<Card> holdings, List<Card> oppHoldings, int myNeed, int tighten) {
        List<Card> visA = without(bundleA, faceDown);
        List<Card> visB = without(bundleB, faceDown);
        boolean faceInA = bundleA.contains(faceDown);

        // ① 현실적 선택자 모델: 사람은 즉시 코인뿐 아니라 자기 손패와의 시너지·뒷면 기대값까지 본다.
        int chooserValA = predictedChooserValue(visA, faceInA, oppHoldings);
        int chooserValB = predictedChooserValue(visB, !faceInA, oppHoldings);
        boolean chooserTakesA = chooserValA > chooserValB
                || (chooserValA == chooserValB && bundleA.size() >= bundleB.size());

        int valueA = myKeepValue(holdings, bundleA, tighten);
        int valueB = myKeepValue(holdings, bundleB, tighten);
        int predMine = chooserTakesA ? valueB : valueA;
        int worstMine = Math.min(valueA, valueB);
        // ① 상시 maximin: 바닥(BASE) 위에 종반 가중을 더한다(중반에도 무방비 분할 억제).
        int mmw = clamp(BASE_MAXIMIN_WEIGHT
                + (TUNING.maximinWeight() - BASE_MAXIMIN_WEIGHT) * tighten / 100, 0, 100);
        int keep = (int) ((long) predMine * (100 - mmw) / 100 + (long) worstMine * mmw / 100);

        List<Card> theirBundle = chooserTakesA ? bundleA : bundleB;
        int theirStandalone = standaloneValue(theirBundle, tighten);
        int oppSynergy = synergy(oppHoldings, theirBundle);
        int theirs = theirStandalone + TUNING.denyWeight() * oppSynergy / 100;

        int visTheirs = chooserTakesA ? BotCardEvaluator.bestCashCoin(visA) : BotCardEvaluator.bestCashCoin(visB);
        int visMine = chooserTakesA ? BotCardEvaluator.bestCashCoin(visB) : BotCardEvaluator.bestCashCoin(visA);
        int lure = visTheirs - visMine;
        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2;

        long score = 0L;
        if (myNeed > 0 && secures(holdings, bundleA, myNeed) && secures(holdings, bundleB, myNeed)) {
            score += WIN_SECURE;
        }
        score += (keep - theirs) * 10_000L + keep * 10L;
        score += (long) lure * TUNING.lureWeight() * (100 - tighten) / 100;
        score += hiddenCurseDumpScore(theirBundle, faceDown);
        score += balanced ? 1L : 0L;
        return score;
    }

    /**
     * ③ 뒷면 숨김 저주 떠넘김(미끼의 일종): 뒷면 카드가 저주이고 그게 선택자가 가져갈 것으로 예측된
     * 묶음이면 보너스 — 선택자는 뒷면을 못 보므로 공개 카드의 매력에 끌려 저주까지 떠안는다.
     *
     * <p><b>앞면이 보이는 저주는 생각하는 상대에게 절대 못 넘긴다</b>(상대는 그냥 다른 묶음을 집는다).
     * 그래서 "보이는 저주를 상대 묶음에 몰아넣는" 라우팅은 두지 않는다 — 안 통할뿐더러 무방비하게
     * 저주를 떠안은 묶음을 만들어 바보같아 보인다. 보이는 저주를 미끼로 쓰는 것은 기존 {@code lure} 항이
     * 자연히 처리한다(좋은 카드를 뒷면에 숨겨 저주 보이는 내 묶음을 상대가 피하게 함).
     */
    private static long hiddenCurseDumpScore(List<Card> theirBundle, Card faceDown) {
        return (faceDown instanceof CursedCard && theirBundle.contains(faceDown)) ? CURSE_HIDDEN_DUMP : 0L;
    }

    /**
     * ① 선택자(상대)가 이 묶음을 얼마나 탐낼지 예측한다: 즉시 코인 + 상대 손패와의 시너지
     * (자기 색 연속을 완성하는 묶음에 끌림) + 뒷면 카드 기대 가치. S5 의 "공개 코인 최대" 가정보다
     * 사람의 실제 선택에 가깝다.
     */
    private static int predictedChooserValue(List<Card> visible, boolean hasFaceDown, List<Card> oppHoldings) {
        int coin = BotCardEvaluator.bestCashCoin(visible) * 100;
        int oppSyn = synergy(oppHoldings, visible);
        int faceDown = hasFaceDown ? FACEDOWN_BONUS : 0;
        return coin + oppSyn + faceDown;
    }

    // ─── 선택(분배) ───────────────────────────────────────────────────────────────
    // ②는 분할 시 상대 선택 예측의 문제이지 봇 자신의 선택 문제가 아니므로 S5 와 동일하게 둔다.

    @Override
    public int decideChoice(ChoiceContext context) {
        ChoiceView view = context.view();
        List<Card> holdings = context.holdings();
        List<Card> oppHoldings = context.opponentHoldings();
        int myNeed = need(context.winningCoins(), context.myCoins());
        int tighten = tighten(context.myCoins(), context.opponentCoins(), context.winningCoins());

        int best = 0;
        long bestScore = Long.MIN_VALUE;
        int bestSize = -1;
        int bundleCount = view.bundles().size();
        for (int i = 0; i < bundleCount; i++) {
            BundleView mine = view.bundle(i);
            BundleView other = view.bundle((i + 1) % bundleCount);
            long score = scoreChoice(mine, other, holdings, oppHoldings, myNeed, tighten);
            if (score > bestScore || (score == bestScore && mine.size() > bestSize)) {
                best = i;
                bestScore = score;
                bestSize = mine.size();
            }
        }
        return best;
    }

    private long scoreChoice(BundleView mine, BundleView other, List<Card> holdings,
            List<Card> oppHoldings, int myNeed, int tighten) {
        List<Card> mineVisible = mine.visibleCards();
        long score = myKeepValue(holdings, mineVisible, tighten);
        if (mine.hasFaceDown()) score += FACEDOWN_BONUS;
        if (secures(holdings, mineVisible, myNeed)) score += WIN_SECURE;

        int theirCoin = BotCardEvaluator.bestCashCoin(other.visibleCards()) * 100;
        score -= (long) theirCoin * tighten * TUNING.denyWeight() / 10_000;

        int oppSynergy = synergy(oppHoldings, other.visibleCards());
        score -= (long) oppSynergy * TUNING.denyWeight() / 100;

        return score;
    }

    // ─── 환금 ─────────────────────────────────────────────────────────────────────

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        return options.stream()
                .sorted(Comparator.comparingInt(this::helperDraftScore).reversed())
                .limit(chooseCount)
                .toList();
    }

    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        return CashInPlanOptimizer.planWithHoldS6(context, opponentCoins);
    }

    // ─── 평가 보조 ────────────────────────────────────────────────────────────────

    private static int myKeepValue(List<Card> holdings, List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int synergy = synergy(holdings, bundle) * (100 - tighten) / 100;
        return coin + synergy;
    }

    private static int standaloneValue(List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int potential = BotCardEvaluator.potentialScore(bundle) * (100 - tighten) / 100;
        return coin + potential;
    }

    private static int synergy(List<Card> holdings, List<Card> bundle) {
        List<Card> combined = new ArrayList<>(holdings);
        combined.addAll(bundle);
        return BotCardEvaluator.potentialScore(combined) - BotCardEvaluator.potentialScore(holdings);
    }

    private static boolean secures(List<Card> holdings, List<Card> bundle, int myNeed) {
        if (myNeed <= 0) return false;
        List<Card> combined = new ArrayList<>(holdings);
        combined.addAll(bundle);
        return BotCardEvaluator.bestCashCoin(combined) >= myNeed;
    }

    private static int need(int winningCoins, int coins) {
        return Math.max(0, winningCoins - coins);
    }

    private int tighten(int myCoins, int opponentCoins, int winningCoins) {
        if (winningCoins <= 0) return 0;
        int closest = Math.min(need(winningCoins, myCoins), need(winningCoins, opponentCoins));
        int endzone = winningCoins * TUNING.endzonePct() / 100;
        if (endzone <= 0 || closest >= endzone) return 0;
        return clamp((endzone - closest) * 100 / endzone, 0, 100);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * 도우미 픽 점수. 드래프트는 게임 준비 시점이라 손패가 없어 손패 반영이 구조적으로 불가능하다
     * (실효 개선은 환금 단계 활용 — {@link CashInPlanOptimizer#planWithHoldS6}). S5 의 가중치를 유지한다.
     */
    private int helperDraftScore(HelperCard helper) {
        return switch (helper.kind()) {
            case LUCKY -> 105;
            case ALPHA -> 100;
            case LEO, CUCKOO -> 88;
            case VIPER -> 86;
            case TUSKER -> 80;
            case CROC_BROTHERS -> 70;
            case JUNK_DEALER -> 60;
            case DOUG -> 56;
        };
    }

    private List<Card> without(List<Card> cards, Card excluded) {
        List<Card> copy = new ArrayList<>(cards);
        copy.remove(excluded);
        return copy;
    }
}
