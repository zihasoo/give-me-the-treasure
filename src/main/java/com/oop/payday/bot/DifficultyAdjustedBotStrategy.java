package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.HelperDraftContext;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.helper.HelperCard;

/**
 * S8 을 공통 기반으로 쓰되, 입력 정보와 일부 결정을 단순화해 체감 난이도를 나눈다.
 */
public final class DifficultyAdjustedBotStrategy implements BotStrategy {

    public enum Level {
        EASY("쉬움"),
        NORMAL("중간"),
        HARD("어려움");

        private final String displayName;

        Level(String displayName) {
            this.displayName = displayName;
        }
    }

    private final Level level;
    private final S8BotStrategy hard = new S8BotStrategy();

    public DifficultyAdjustedBotStrategy(Level level) {
        this.level = level;
    }

    @Override
    public String displayName() {
        return level.displayName;
    }

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        return switch (level) {
            case HARD -> hard.decideSplit(context);
            case NORMAL -> hard.decideSplit(withLimitedMemory(context));
            case EASY -> ThreadLocalRandom.current().nextInt(100) < 35
                    ? simpleSplit(context.hand())
                    : hard.decideSplit(withLimitedMemory(context));
        };
    }

    @Override
    public int decideChoice(ChoiceContext context) {
        return switch (level) {
            case HARD -> hard.decideChoice(context);
            case NORMAL -> hard.decideChoice(withLimitedMemory(context));
            case EASY -> ThreadLocalRandom.current().nextInt(100) < 45
                    ? visibleCoinChoice(context.view().bundles())
                    : hard.decideChoice(withLimitedMemory(context));
        };
    }

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<List<Card>> memberHoldings) {
        if (level == Level.EASY) {
            return BotStrategy.super.decideTeamDistribution(acquired, memberHoldings);
        }
        return hard.decideTeamDistribution(acquired, memberHoldings);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount, HelperDraftContext context) {
        if (level == Level.HARD) {
            return hard.decideHelpers(options, chooseCount, context);
        }
        if (level == Level.NORMAL) {
            return hard.decideHelpers(options, chooseCount, new HelperDraftContext(
                    context.teamSize(), 7, context.officer(), context.winningCoins()));
        }
        return options.stream()
                .sorted(Comparator.comparingInt(DifficultyAdjustedBotStrategy::simpleHelperScore).reversed())
                .limit(chooseCount)
                .toList();
    }

    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        int visibleOpponentCoins = level == Level.HARD ? opponentCoins : 0;
        return hard.planCashIn(context, visibleOpponentCoins);
    }

    private static SplitContext withLimitedMemory(SplitContext context) {
        return new SplitContext(
                context.hand(),
                context.holdings(),
                context.myCoins(),
                context.opponentCoins(),
                context.winningCoins(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static ChoiceContext withLimitedMemory(ChoiceContext context) {
        return new ChoiceContext(
                context.view(),
                context.holdings(),
                context.myCoins(),
                context.opponentCoins(),
                context.winningCoins(),
                List.of(),
                List.of(),
                List.of(),
                null);
    }

    private static SplitDecision simpleSplit(List<Card> hand) {
        List<Card> sorted = new ArrayList<>(hand);
        sorted.sort(Comparator.comparingInt(DifficultyAdjustedBotStrategy::simpleCardScore).reversed());
        List<Card> bundleA = new ArrayList<>(sorted.subList(0, 2));
        List<Card> bundleB = new ArrayList<>(sorted.subList(2, sorted.size()));
        Card faceDown = sorted.get(sorted.size() - 1);
        return new SplitDecision(bundleA, bundleB, faceDown);
    }

    private static int visibleCoinChoice(List<BundleView> bundles) {
        int best = 0;
        int bestScore = Integer.MIN_VALUE;
        int bestSize = -1;
        for (int i = 0; i < bundles.size(); i++) {
            BundleView bundle = bundles.get(i);
            int score = BotCardEvaluator.bestCashCoin(bundle.visibleCards());
            if (score > bestScore || (score == bestScore && bundle.size() > bestSize)) {
                best = i;
                bestScore = score;
                bestSize = bundle.size();
            }
        }
        return best;
    }

    private static int simpleCardScore(Card card) {
        if (card.isWild()) return 100;
        if (card instanceof CursedCard) return -20;
        return BotCardEvaluator.bestCashCoin(List.of(card));
    }

    private static int simpleHelperScore(HelperCard helper) {
        return switch (helper.kind()) {
            case LUCKY -> 90;
            case ALPHA -> 80;
            case LEO, CUCKOO -> 70;
            case VIPER -> 60;
            case TUSKER -> 50;
            case CROC_BROTHERS -> 40;
            case JUNK_DEALER -> 30;
            case DOUG -> 20;
        };
    }
}
