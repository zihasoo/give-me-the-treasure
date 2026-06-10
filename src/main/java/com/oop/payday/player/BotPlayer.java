package com.oop.payday.player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.CashSink;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
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
        return play(strategy, "봇");
    }

    /** 이름을 지정하는 플레이용 봇. 대기실에서 "봇 1", "봇 2" 처럼 구분된 이름을 그대로 인게임에 반영한다. */
    public static BotPlayer play(BotStrategy strategy, String name) {
        return new BotPlayer(name, strategy, 2000, 4000, 850, 800, 1400);
    }

    @Override
    public int revealPaceMillis() { return pace; }

    private int nextCashPaceMillis() {
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
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<Player> members) {
        think();
        List<List<Card>> memberHoldings = new ArrayList<>();
        for (Player member : members) {
            memberHoldings.add(new ArrayList<>(member.holdings()));
        }
        return strategy.decideTeamDistribution(acquired, memberHoldings);
    }

    @Override
    public void beginCashIn(CashInContext snapshot, CashSink sink) {
        // 봇은 자기 가상 스레드에서 계획을 세워 페이스대로 하나씩 제출하고 마지막에 패스한다.
        // 게임 스레드를 막지 않으므로 think/페이싱을 봇이 직접 소유한다(엔진은 큐만 기다린다).
        Thread.ofVirtual().name("bot-cash-" + name()).start(() -> {
            List<CashInAction> plan = strategy.planCashIn(snapshot);
            for (CashInAction action : plan) {
                pause(nextCashPaceMillis());
                sink.submit(action);
            }
            pause(nextCashPaceMillis());
            sink.pass();
        });
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

    /** 환금 행동 사이 봇 페이스 대기(ms). 0이면 즉시. */
    private static void pause(int millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
