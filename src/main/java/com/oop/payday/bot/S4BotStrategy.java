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
 * S3 대비 두 가지 전략 축을 추가한 실험 전략.
 *
 * <ol>
 *   <li><b>환금 보류(§4.1)</b>: 성장 여지가 있는 세트를 즉시 환금하지 않고 다음 라운드까지 모아 더 큰 세트로
 *       키운다. 초선형 환금표에서 한 등급 키울 때 코인 증분이 크므로 한도 여유가 있을 때는 보류가 이득이다.
 *       {@link CashInPlanOptimizer#planWithHold}가 담당한다.</li>
 *   <li><b>상대 holdings 견제(§4.2)</b>: 분할과 선택 시 "상대 묶음을 상대 기존 패에 더했을 때 늘어나는
 *       시너지({@code oppSynergy})"를 페널티로 추가해, 상대가 같은 색 연속처럼 고가 세트를 완성하지 못하도록
 *       방해한다. 이 견제는 S3와 달리 종반에도 끄지 않는다(사용자 직관과 일치).</li>
 * </ol>
 *
 * <p>파라미터 구조는 {@link S3BotStrategy.S3Tuning}을 그대로 재사용한다. {@code denyWeight}는
 * 기존 "종반 즉시 코인 견제"와 새 "oppSynergy 견제" 두 곳에 동시 적용된다.
 */
public final class S4BotStrategy implements BotStrategy {

    /** S3 파라미터를 그대로 재사용. {@code denyWeight}가 oppSynergy 견제에도 적용된다. */
    private static final S3BotStrategy.S3Tuning TUNING = S3BotStrategy.S3Tuning.DEFAULT;

    private static final long WIN_SECURE = 1_000_000_000L;
    private static final int FACEDOWN_BONUS = 20;

    @Override
    public String displayName() {
        return "S4";
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
        int visA = BotCardEvaluator.bestCashCoin(without(bundleA, faceDown));
        int visB = BotCardEvaluator.bestCashCoin(without(bundleB, faceDown));
        boolean chooserTakesA = visA > visB || (visA == visB && bundleA.size() >= bundleB.size());

        int valueA = myKeepValue(holdings, bundleA, tighten);
        int valueB = myKeepValue(holdings, bundleB, tighten);
        int predMine = chooserTakesA ? valueB : valueA;
        int worstMine = Math.min(valueA, valueB);
        int mmw = TUNING.maximinWeight() * tighten / 100;
        int keep = (int) ((long) predMine * (100 - mmw) / 100 + (long) worstMine * mmw / 100);

        // 상대 몫 묶음: S3 단독 가치 + S4 oppSynergy 견제(종반에도 유지 — tighten 곱 없음)
        List<Card> theirBundle = chooserTakesA ? bundleA : bundleB;
        int theirStandalone = standaloneValue(theirBundle, tighten);
        int oppSynergy = synergy(oppHoldings, theirBundle);
        int theirs = theirStandalone + TUNING.denyWeight() * oppSynergy / 100;

        int visTheirs = chooserTakesA ? visA : visB;
        int visMine = chooserTakesA ? visB : visA;
        int lure = visTheirs - visMine;
        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2;

        long score = 0L;
        if (myNeed > 0 && secures(holdings, bundleA, myNeed) && secures(holdings, bundleB, myNeed)) {
            score += WIN_SECURE;
        }
        score += (keep - theirs) * 10_000L + keep * 10L;
        score += (long) lure * TUNING.lureWeight() * (100 - tighten) / 100;
        score += balanced ? 1L : 0L;
        return score;
    }

    // ─── 선택(분배) ───────────────────────────────────────────────────────────────

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

        // S3 기존 견제: 종반에 상대 즉시 코인을 깎는다
        int theirCoin = BotCardEvaluator.bestCashCoin(other.visibleCards()) * 100;
        score -= (long) theirCoin * tighten * TUNING.denyWeight() / 10_000;

        // S4 추가 견제: 상대 묶음이 상대 패와 시너지가 크면 내가 가져가 차단(종반 무관)
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
        return CashInPlanOptimizer.planWithHold(context, opponentCoins);
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

    /** 묶음을 기존 패에 더했을 때 늘어나는 잠재력(한계 시너지). */
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
