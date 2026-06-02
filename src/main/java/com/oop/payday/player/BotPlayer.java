package com.oop.payday.player;

import java.util.List;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 봇 플레이어. 모든 의사결정을 주입받은 {@link BotStrategy} 에 위임한다(전략 패턴).
 * 전략만 갈아끼우면 봇의 실력/방식을 바꿀 수 있다.
 */
public final class BotPlayer extends Player {

    private final BotStrategy strategy;

    public BotPlayer(String name, BotStrategy strategy) {
        super(name);
        this.strategy = strategy;
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        return strategy.decideSplit(hand);
    }

    @Override
    public int decideChoice(ChoiceView view) {
        return strategy.decideChoice(view);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        return strategy.decideHelpers(options, chooseCount);
    }

    @Override
    public List<CashInAction> decideCashIn(CashInContext context) {
        return strategy.decideCashIn(context);
    }

    @Override
    public boolean isBot() {
        return true;
    }
}
