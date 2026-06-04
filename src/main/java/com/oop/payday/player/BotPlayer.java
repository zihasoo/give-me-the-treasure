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
 *
 * <p>정적 팩토리 {@link #test}와 {@link #play}로 생성한다:
 * <ul>
 *   <li>{@code test} — 딜레이 없음. 자동 테스트·빠른 반복용.
 *   <li>{@code play} — 결정마다 사람처럼 생각 시간 + 환금 단계별 공개 텀(플레이용).
 * </ul>
 */
public final class BotPlayer extends Player {

    private final BotStrategy strategy;
    private final int thinkMin;
    private final int thinkMax;
    private final int pace;
    private final int cashPaceMin;
    private final int cashPaceMax;

    private BotPlayer(String name, BotStrategy strategy, int thinkMin, int thinkMax, int pace,
            int cashPaceMin, int cashPaceMax) {
        super(name);
        this.strategy = strategy;
        this.thinkMin = thinkMin;
        this.thinkMax = thinkMax;
        this.pace = pace;
        this.cashPaceMin = cashPaceMin;
        this.cashPaceMax = cashPaceMax;
    }

    /** 딜레이 없는 테스트 봇. */
    public static BotPlayer test(BotStrategy strategy) {
        return new BotPlayer("봇", strategy, 0, 0, 0, 0, 0);
    }

    /** 생각 시간 + 환금 이벤트 루프에서 행동 사이 사람 같은 텀이 있는 플레이용 봇. */
    public static BotPlayer play(BotStrategy strategy) {
        return new BotPlayer("봇", strategy, 2000, 4000, 850, 600, 1400);
    }

    @Override
    public int revealPaceMillis() { return pace; }

    @Override
    public int nextCashPaceMillis() {
        if (cashPaceMax <= 0) return 0;
        return ThreadLocalRandom.current().nextInt(cashPaceMin, cashPaceMax + 1);
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        think();
        return strategy.decideSplit(hand);
    }

    @Override
    public int decideChoice(ChoiceView view) {
        think();
        return strategy.decideChoice(view);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        think();
        return strategy.decideHelpers(options, chooseCount);
    }

    @Override
    public List<CashInAction> decideCashIn(CashInContext context) {
        // 환금은 이벤트 루프가 행동마다 호출하므로 페이스는 nextCashPaceMillis 로 따로 준다(여기선 즉시 결정).
        return strategy.decideCashIn(context);
    }

    @Override
    public boolean isBot() {
        return true;
    }

    private void think() {
        if (thinkMin <= 0 && thinkMax <= 0) return;
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(thinkMin, thinkMax + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
