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
 * S4 기반에서 환금 보류 판단을 개선한 전략.
 *
 * <p>S4의 {@link CashInPlanOptimizer#planWithHold}는 보류 후보를 "한 단계 코인 증분(growthCoin)"으로
 * 평가하고 버림 더미에 없으면 무조건 성장 가능으로 본다. S5는 이를
 * {@link CashInPlanOptimizer#planWithHoldS5}로 대체해 다음 두 가지를 개선한다.
 *
 * <ol>
 *   <li><b>다단계 코인 증분</b>: 한 단계만이 아니라 최대 크기까지 단계별 기대 코인 증분을 고려한다.</li>
 *   <li><b>상대 손패 반영 예상 획득 턴</b>: 필요한 카드가 버림 더미(슬쩍하기 가능, 빠름) /
 *       상대 손패(상대가 써야 돌아옴, 느림) / 미지 덱(미지 후보 수 비례, 중간) 중 어디 있는지로
 *       예상 획득 턴을 계산해 보류 가치를 연속 점수화한다.</li>
 * </ol>
 *
 * <p>분할·선택 로직은 S4와 동일하다.
 */
public final class S5BotStrategy implements BotStrategy {

    private static final S3BotStrategy.S3Tuning TUNING = S3BotStrategy.S3Tuning.DEFAULT;

    private static final long WIN_SECURE = 1_000_000_000L;
    private static final int FACEDOWN_BONUS = 20;

    @Override
    public String displayName() {
        return "S5";
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
        return CashInPlanOptimizer.planWithHoldS5(context, opponentCoins);
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
     * S5 도우미 픽 점수. S4 대비 조정:
     * TUSKER ↑(보류 세트를 들고 있어 손패가 자주 한도를 넘음),
     * VIPER ↑(저주 제거로 보류 공간 확보),
     * CROC_BROTHERS ↑(TUSKER·VIPER 복사 가치 상승),
     * JUNK_DEALER ↑(버림 더미 와일드로 보류 세트 완성 지원).
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
