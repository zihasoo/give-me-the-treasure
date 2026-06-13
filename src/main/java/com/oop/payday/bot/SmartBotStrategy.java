package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 점수 기반 봇 전략. 기존 규칙 기반 전략의 안정성은 유지하되, 환금 조합 최적화와
 * 카드 잠재력 평가를 적극적으로 반영한다.
 */
public final class SmartBotStrategy implements BotStrategy {

    @Override
    public String displayName() {
        return "점수 기반";
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
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
                long score = scoreSplit(bundleA, bundleB, faceDown);
                if (score > bestScore) {
                    bestScore = score;
                    best = new SplitDecision(bundleA, bundleB, faceDown);
                }
            }
        }
        return best;
    }

    @Override
    public int decideChoice(ChoiceView view) {
        int best = 0;
        int bestScore = Integer.MIN_VALUE;
        int bestSize = -1;
        for (int i = 0; i < view.bundles().size(); i++) {
            var bundle = view.bundle(i);
            int score = BotCardEvaluator.bundleScore(bundle.visibleCards());
            if (bundle.hasFaceDown()) {
                score += 20;
            }
            if (score > bestScore || (score == bestScore && bundle.size() > bestSize)) {
                best = i;
                bestScore = score;
                bestSize = bundle.size();
            }
        }
        return best;
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        return options.stream()
                .sorted(Comparator.comparingInt(this::helperDraftScore).reversed())
                .limit(chooseCount)
                .toList();
    }

    @Override
    public List<CashInAction> planCashIn(CashInContext context) {
        return CashInPlanOptimizer.plan(context);
    }

    private long scoreSplit(List<Card> bundleA, List<Card> bundleB, Card faceDown) {
        // 상대 선택 예측: coinValue 기준(HeuristicBot·대부분 봇이 실제로 쓰는 방식)
        int visibleCoinA = BotCardEvaluator.bestCashCoin(without(bundleA, faceDown));
        int visibleCoinB = BotCardEvaluator.bestCashCoin(without(bundleB, faceDown));
        boolean chooserTakesA = visibleCoinA > visibleCoinB
                || (visibleCoinA == visibleCoinB && bundleA.size() >= bundleB.size());
        List<Card> mine = chooserTakesA ? bundleB : bundleA;
        List<Card> theirs = chooserTakesA ? bundleA : bundleB;

        // 내 묶음·상대 묶음 평가는 잠재력 포함 full score 사용
        int mineScore = BotCardEvaluator.bundleScore(mine);
        int theirScore = BotCardEvaluator.bundleScore(theirs);
        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2;
        return (long) (mineScore - theirScore) * 10_000L
                + (long) mineScore * 10L
                + (balanced ? 1L : 0L);
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
