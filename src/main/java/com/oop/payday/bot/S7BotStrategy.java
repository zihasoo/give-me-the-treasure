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
 * S6 기반에서 <b>사람 vs S6 플레이 로그(2026-06-16, 사람 32:20 승)</b>로 드러난 약점 셋을 고친 전략.
 *
 * <p>로그 분석이 짚은 패인은 서로 맞물려 있었다.
 *
 * <ol>
 *   <li><b>① 선택 오판으로 보물(와일드) 헌납</b> — S6 의 {@link S6BotStrategy#decideChoice}는 뒷면 한 장을
 *       고정 보너스 20 으로만 봐서, 사람이 <i>얇은 묶음 뒤에 굉장한 보물을 숨기고 두꺼운 미끼 묶음을 내미는</i>
 *       분할에 매번 걸렸다(R3). 사람은 그 와일드로 9·15코인 대형 세트를 뽑아 게임을 끝냈다.
 *       S7 은 {@link #hiddenEv}로 <b>적대적 뒷면 추론</b>(덜 매력적으로 보이는 묶음일수록 분할자가 값진
 *       카드를 숨겼을 확률↑)을 쓰고, 보이는 와일드는 {@link #wildSwing}으로 강하게 확보/견제한다.</li>
 *   <li><b>② 저주를 부채로 안 봄</b> — S6 의 keep/choice 평가는 저주를 0(중립)으로 봐 저주 묶음을 태연히
 *       떠안았고(R2·R4), 저주가 보유 한도를 잡아먹어 키울 세트를 즉시 환금하게 만들었다(R7). 게다가
 *       TUSKER 로 저주를 끌어안았다(R3). S7 은 {@link #CURSE_LIABILITY}로 저주를 음의 가치로 깔고,
 *       환금 단계는 {@link CashInPlanOptimizer.Tuning#S7}(저주 보유공간 제외 + TUSKER 가드)로 돌린다.</li>
 *   <li><b>③ 3장 세트 난사</b> — S6 는 같은색 연속(천장 4장=9·5장=15)을 3장(6코인)에서 즉시 환금했다.
 *       S7 은 보류 임계를 낮추고 같은색 연속 성장에 가중을 둔다({@link CashInPlanOptimizer.Tuning#S7}).
 *       ②로 보유 한도가 트여야 실제로 보류가 가능해지므로 ②·③은 함께 작동한다.</li>
 * </ol>
 *
 * <p>분할 예측({@link #predictedChooserValue})도 같은 적대적 뒷면 모델을 써, 봇이 값진 카드를 얇은 묶음에
 * 숨겨 사람에게 흘리던 누수(R4·R8)를 줄인다.
 */
public final class S7BotStrategy implements BotStrategy {

    // 자기복제 A/B 스윕으로 튜닝한 S6 가중치를 계승한다.
    /** 종반 구간 폭(%): 누군가 {@code (100 − ENDZONE_PCT)}% 도달 시점부터 긴박도가 켜진다. */
    private static final int ENDZONE_PCT = 30;
    /** 미끼 유도력(블러핑) 가중치. 종반엔 자동 페이드. */
    private static final int LURE_WEIGHT = 40;
    /** 종반에 분할 keep 가치를 기대값→최악보장(maximin)으로 끄는 정도(0~100). */
    private static final int MAXIMIN_WEIGHT = 100;
    /** 상대에게 넘기는 즉시 코인·oppSynergy 견제 강도(0~200, 100=코인 전액). */
    private static final int DENY_WEIGHT = 100;

    private static final long WIN_SECURE = 1_000_000_000L;

    /** 상시 maximin 안전장치 바닥(0~100). 종반에 {@code MAXIMIN_WEIGHT}까지 상승한다. */
    private static final int BASE_MAXIMIN_WEIGHT = 50;

    /** 뒷면으로 숨겨 상대 묶음에 저주를 떠넘길 때의 보너스(선택자가 못 알아챔). */
    private static final int CURSE_HIDDEN_DUMP = 9_000;

    // ─── ① 적대적 뒷면·보물 평가 (S6 의 FACEDOWN_BONUS=20 대체) ───
    /** 정체 모를 뒷면 한 장의 기본 기대값(코인×100 단위). S6 의 20 보다 훨씬 크게 본다. (봇 자신의 선택용) */
    private static final int BASE_HIDDEN_EV = 60;
    /** 공개 카드 수 격차 한 장이 '미끼 묶음 vs 숨김 묶음' 판단에 더하는 의심 가중. */
    private static final int COUNT_LURE = 60;
    /** 이 묶음이 상대 묶음보다 덜 매력적인 정도에 비례해 뒷면 기대값을 키우는 배율(%). */
    private static final int HIDDEN_SUSPICION = 120;
    /** 보이는 와일드(굉장한 보물) 확보/견제 스윙. 와일드는 15코인 세트를 완성할 수 있어 크게 본다. */
    private static final int WILD_GRAB = 600;

    // ─── 선택자 예측: 카드 물량 기반 (이유 없는 1:4 분할 방지) ───
    /** 선택자에게 카드 1장이 갖는 재료 가치(코인×100 단위). 장수가 많은 묶음이 더 매력적이라고 본다. */
    private static final int CARD_MATERIAL = 70;
    /** 뒷면 카드 1장이 정체 모름에 더하는 적대적 프리미엄(분할자가 값진 걸 숨겼을 약간의 의심). */
    private static final int HIDDEN_PREMIUM = 60;

    // ─── ② 저주 부채 ───
    /** keep/choice 평가에서 저주 한 장이 무는 음의 가치(처분 코스트·보유 한도 점유의 근사). */
    private static final int CURSE_LIABILITY = 120;

    @Override
    public String displayName() {
        return "S7";
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

        // ① 현실적 선택자 모델: 즉시 코인 + 카드 물량(장수) + 상대 손패 시너지 + 보이는 와일드 + 뒷면 기대값.
        int chooserValA = predictedChooserValue(visA, faceInA, oppHoldings);
        int chooserValB = predictedChooserValue(visB, !faceInA, oppHoldings);
        boolean chooserTakesA = chooserValA > chooserValB
                || (chooserValA == chooserValB && bundleA.size() >= bundleB.size());

        int valueA = myKeepValue(holdings, bundleA, tighten);
        int valueB = myKeepValue(holdings, bundleB, tighten);
        int predMine = chooserTakesA ? valueB : valueA;
        int worstMine = Math.min(valueA, valueB);
        int mmw = clamp(BASE_MAXIMIN_WEIGHT
                + (MAXIMIN_WEIGHT - BASE_MAXIMIN_WEIGHT) * tighten / 100, 0, 100);
        int keep = (int) ((long) predMine * (100 - mmw) / 100 + (long) worstMine * mmw / 100);

        List<Card> theirBundle = chooserTakesA ? bundleA : bundleB;
        int theirStandalone = standaloneValue(theirBundle, tighten);
        int oppSynergy = synergy(oppHoldings, theirBundle);
        int theirs = theirStandalone + DENY_WEIGHT * oppSynergy / 100;

        int visTheirs = chooserTakesA ? BotCardEvaluator.bestCashCoin(visA) : BotCardEvaluator.bestCashCoin(visB);
        int visMine = chooserTakesA ? BotCardEvaluator.bestCashCoin(visB) : BotCardEvaluator.bestCashCoin(visA);
        int lure = visTheirs - visMine;

        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2;

        long score = 0L;
        if (myNeed > 0 && secures(holdings, bundleA, myNeed) && secures(holdings, bundleB, myNeed)) {
            score += WIN_SECURE;
        }
        score += (keep - theirs) * 10_000L + keep * 10L;
        score += (long) lure * LURE_WEIGHT * (100 - tighten) / 100;
        score += hiddenCurseDumpScore(theirBundle, faceDown);
        score += balanced ? 1L : 0L;
        return score;
    }

    /**
     * ③ 뒷면 숨김 저주 떠넘김: 뒷면 카드가 저주이고 선택자가 가져갈 것으로 예측된 묶음이면 보너스 —
     * 선택자는 뒷면을 못 보므로 공개 카드의 매력에 끌려 저주까지 떠안는다. 앞면이 보이는 저주는
     * 생각하는 상대에게 못 넘기므로(그냥 다른 묶음을 집음) 라우팅하지 않는다.
     */
    private static long hiddenCurseDumpScore(List<Card> theirBundle, Card faceDown) {
        return (faceDown instanceof CursedCard && theirBundle.contains(faceDown)) ? CURSE_HIDDEN_DUMP : 0L;
    }

    /**
     * ① 선택자(상대)가 이 묶음을 얼마나 탐낼지 예측: 즉시 코인 + <b>카드 물량</b>(장수에 비례한 재료 가치)
     * + 상대 손패 시너지 + 보이는 와일드 + 뒷면 한 장의 기대값.
     *
     * <p><b>핵심은 물량이다.</b> 사람은 4장 묶음을 1장(+뒷면) 묶음보다 선호한다(재료가 많아 미래 세트를
     * 더 짓는다). 뒷면은 카드 1장 + 약간의 미지 프리미엄일 뿐 4장을 못 이긴다. 이렇게 둬야 봇이
     * "사람이 빈 묶음을 가져갈 것"이라 착각해 이유 없이 1:4 로 나눠 4장을 헌납하는 일이 사라진다.
     * 저주는 음의 재료라 묶음 매력을 낮춘다(선택자가 피함 → 뒷면 저주 떠넘김과 호응).
     */
    private static int predictedChooserValue(List<Card> mineVisible, boolean hasFaceDown, List<Card> oppHoldings) {
        int coin = BotCardEvaluator.bestCashCoin(mineVisible) * 100;
        int material = 0;
        for (Card card : mineVisible) {
            material += (card instanceof CursedCard) ? -CARD_MATERIAL : CARD_MATERIAL;
        }
        int wild = wildValue(mineVisible); // 보이는 와일드는 선택자가 무조건 챙긴다 → 봇이 와일드를 노출하면 잃는다고 예측.
        int oppSyn = synergy(oppHoldings, mineVisible);
        int hidden = hasFaceDown ? CARD_MATERIAL + HIDDEN_PREMIUM : 0; // 뒷면도 카드 1장 + 약간의 미지 프리미엄.
        return coin + material + wild + oppSyn + hidden;
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
        List<Card> otherVisible = other.visibleCards();

        // ① 보이는 와일드 확보는 myKeepValue 가 처리(아래). 적대적 뒷면 추론: 이 묶음이 공개상 빈약할수록
        //    분할자가 값진 카드를 숨겼을 확률↑. 종반엔 더 크게.
        long score = myKeepValue(holdings, mineVisible, tighten);
        if (mine.hasFaceDown()) {
            score += (long) hiddenEv(mineVisible, otherVisible) * (200 + tighten) / 200;
        }
        // ① 보이는 와일드는 상대에게 절대 안 남긴다(견제).
        if (otherVisible.stream().anyMatch(Card::isWild)) score -= WILD_GRAB;
        if (secures(holdings, mineVisible, myNeed)) score += WIN_SECURE;

        int theirCoin = BotCardEvaluator.bestCashCoin(otherVisible) * 100;
        score -= (long) theirCoin * tighten * DENY_WEIGHT / 10_000;

        int oppSynergy = synergy(oppHoldings, otherVisible);
        score -= (long) oppSynergy * DENY_WEIGHT / 100;

        return score;
    }

    /**
     * ① 적대적 뒷면 기대값(원시): 분할자는 지키고 싶은 값진 카드를 '덜 매력적으로 보이는' 묶음에 뒷면으로
     * 숨겨 선택자를 다른(미끼) 묶음으로 유도한다. 따라서 이 묶음이 상대 묶음보다 공개상 빈약할수록
     * (코인↓·장수↓) 뒷면이 값질 확률이 높다고 본다.
     */
    private static int hiddenEv(List<Card> mineVisible, List<Card> otherVisible) {
        int myVis = BotCardEvaluator.bestCashCoin(mineVisible);
        int otherVis = BotCardEvaluator.bestCashCoin(otherVisible);
        int leanness = (otherVis - myVis) * 100 + (otherVisible.size() - mineVisible.size()) * COUNT_LURE;
        return BASE_HIDDEN_EV + Math.max(0, leanness) * HIDDEN_SUSPICION / 100;
    }

    /** ① 와일드(굉장한 보물) 보유 가치: 대형 세트(최대 15코인)를 완성하는 핵심 자원이라 크게 본다. */
    private static int wildValue(List<Card> cards) {
        return cards.stream().anyMatch(Card::isWild) ? WILD_GRAB : 0;
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
        return CashInPlanOptimizer.plan(context, opponentCoins, CashInPlanOptimizer.Tuning.S7);
    }

    // ─── 평가 보조 ────────────────────────────────────────────────────────────────

    /**
     * 묶음을 내 쪽에 두는 가치. ① 와일드는 보유 가치를 크게 더해(분할 시 와일드를 지키도록), ② 저주는 부채로
     * 깐다(코인 잠재력은 0인데 처분 코스트·보유 한도 점유의 음의 가치).
     */
    private static int myKeepValue(List<Card> holdings, List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int synergy = synergy(holdings, bundle) * (100 - tighten) / 100;
        int wild = wildValue(bundle);
        int curseLiability = curseCount(bundle) * CURSE_LIABILITY;
        return coin + synergy + wild - curseLiability;
    }

    private static int curseCount(List<Card> cards) {
        int count = 0;
        for (Card card : cards) {
            if (card instanceof CursedCard) count++;
        }
        return count;
    }

    private static int standaloneValue(List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int potential = BotCardEvaluator.potentialScore(bundle) * (100 - tighten) / 100;
        int wild = wildValue(bundle); // 상대에게 와일드를 넘기는 건 큰 손해 → theirs 를 키워 회피하게.
        return coin + potential + wild;
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
        int endzone = winningCoins * ENDZONE_PCT / 100;
        if (endzone <= 0 || closest >= endzone) return 0;
        return clamp((endzone - closest) * 100 / endzone, 0, 100);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    /**
     * 도우미 픽 점수. 드래프트는 손패가 없어 손패 반영이 구조적으로 불가능하다(실효 개선은 환금 단계 활용 —
     * {@link CashInPlanOptimizer#plan}). 환금 보류·견제에 유리한 가중치를 S6 에서 계승한다.
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
