package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * {@link S1BotStrategy} 의 실험적 후속 전략(S2, 현재 버전). 기본 점수 로직은 그대로 두고 두 가지를 더한다:
 *
 * <ul>
 *   <li><b>분할 블러핑 명시화</b>: 상대를 내가 원하는(실제 가치가 낮은) 묶음으로 유도하는
 *       공개 가치 차이를 점수에 명시적으로 더한다. 좋은 카드를 뒷면으로 숨겨 내 묶음을
 *       덜 매력적으로 보이게 만드는 선택을 적극적으로 고른다.</li>
 *   <li><b>승리 임박 환금</b>: 팀이 승리 코인에 가까우면 와일드 보존·미래 잠재력을 무시하고
 *       즉시 모을 수 있는 코인을 최대화한다({@link CashInPlanOptimizer#plan(CashInContext, boolean)}).</li>
 * </ul>
 *
 * <p>나머지 결정({@code decideChoice}, {@code decideHelpers})은 {@link S1BotStrategy} 와 동일하다.
 * 두 전략을 헤드리스로 맞붙여 개선 효과를 A/B 측정하기 위해 별도 클래스로 둔다.
 */
public final class S2BotStrategy implements BotStrategy {

    /** 미끼 유도력 항의 가중치. 코인 우위(×10,000)·내 진행도(×10) 아래의 보조 신호로 둔다. */
    private static final int LURE_WEIGHT = 40;

    @Override
    public String displayName() {
        return "S2";
    }

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        List<Card> hand = context.hand();
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
    public int decideChoice(ChoiceContext context) {
        ChoiceView view = context.view();
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
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        // S2는 자기 팀 코인 기준 승리 임박만 본다(상대 코인 활용은 S3로 분리).
        return CashInPlanOptimizer.plan(context, true);
    }

    private long scoreSplit(List<Card> bundleA, List<Card> bundleB, Card faceDown) {
        // 상대 선택 예측: 공개 coinValue 기준(대부분 봇이 실제로 쓰는 방식)
        int visibleCoinA = BotCardEvaluator.bestCashCoin(without(bundleA, faceDown));
        int visibleCoinB = BotCardEvaluator.bestCashCoin(without(bundleB, faceDown));
        boolean chooserTakesA = visibleCoinA > visibleCoinB
                || (visibleCoinA == visibleCoinB && bundleA.size() >= bundleB.size());
        List<Card> mine = chooserTakesA ? bundleB : bundleA;
        List<Card> theirs = chooserTakesA ? bundleA : bundleB;

        // 내 묶음·상대 묶음 평가는 잠재력 포함 full score 사용
        int mineScore = BotCardEvaluator.bundleScore(mine);
        int theirScore = BotCardEvaluator.bundleScore(theirs);
        // 미끼 유도력: 상대가 가져갈 묶음의 공개 가치가 내 묶음보다 클수록 상대를 그쪽으로
        // 확실히 유도한 것이다(>= 0). 좋은 카드를 내 쪽에 뒷면으로 숨기는 분할을 선호하게 한다.
        int visibleTheirs = chooserTakesA ? visibleCoinA : visibleCoinB;
        int visibleMine = chooserTakesA ? visibleCoinB : visibleCoinA;
        int lure = visibleTheirs - visibleMine;
        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2;
        return (long) (mineScore - theirScore) * 10_000L
                + (long) mineScore * 10L
                + (long) lure * LURE_WEIGHT
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
