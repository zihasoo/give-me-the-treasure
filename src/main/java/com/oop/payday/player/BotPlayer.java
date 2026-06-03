package com.oop.payday.player;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
 *
 * <p>각 결정 전에 사람처럼 "생각하는" 시간을 두어, 봇전이 순식간에 끝나지 않고
 * 화면 연출(묶음 공개·환금 애니메이션)을 눈으로 따라갈 수 있게 한다. 게임 로직은
 * 전용 스레드에서 돌기 때문에 여기서 잠깐 블록해도 UI 응답성에는 영향이 없다.
 */
public final class BotPlayer extends Player {

    private final BotStrategy strategy;

    public BotPlayer(String name, BotStrategy strategy) {
        super(name);
        this.strategy = strategy;
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        think(900, 1700);
        return strategy.decideSplit(hand);
    }

    @Override
    public int decideChoice(ChoiceView view) {
        think(900, 1700);
        return strategy.decideChoice(view);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        think(700, 1200);
        return strategy.decideHelpers(options, chooseCount);
    }

    @Override
    public List<CashInAction> decideCashIn(CashInContext context) {
        think(1000, 1800);
        return strategy.decideCashIn(context);
    }

    @Override
    public boolean isBot() {
        return true;
    }

    /** 사람처럼 보이도록 결정 직전에 무작위로 잠깐 멈춘다(게임 스레드 한정). */
    private void think(int minMillis, int maxMillis) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(minMillis, maxMillis + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
