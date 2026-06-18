package com.oop.payday.player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.bot.BotStrategy.ThinkDelay;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.CashSink;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.HelperDraftContext;
import com.oop.payday.decision.SplitContext;
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
 *   <li>{@code play} — 결정마다(환금 행동 사이 포함) 사람처럼 생각 시간(플레이용).
 * </ul>
 *
 * <p>'생각 시간' 실행은 {@code BotPlayer} 가 소유한다. 전략은 {@link BotStrategy#thinkDelay()} 와
 * {@link BotStrategy#cashInThinkDelay()} 로 대기시간 정책만 제공하고, {@code BotPlayer} 는 {@code paced}
 * 여부에 따라 실제로 기다릴지 결정한다.
 */
public final class BotPlayer extends Player {

    private final BotStrategy strategy;
    private final boolean paced;

    private BotPlayer(String name, BotStrategy strategy, boolean paced) {
        super(name);
        this.strategy = strategy;
        this.paced = paced;
    }

    /** 딜레이 없는 테스트 봇. */
    public static BotPlayer test(BotStrategy strategy) {
        return new BotPlayer("봇", strategy, false);
    }

    /** 생각 시간 + 환금 이벤트 루프에서 행동 사이 사람 같은 텀이 있는 플레이용 봇. */
    public static BotPlayer play(BotStrategy strategy) {
        return play(strategy, "봇");
    }

    /** 이름을 지정하는 플레이용 봇. 대기실에서 "어려움 봇 1" 처럼 구분된 이름을 그대로 인게임에 반영한다. */
    public static BotPlayer play(BotStrategy strategy, String name) {
        return new BotPlayer(name, strategy, true);
    }

    /** 이 봇이 위임하는 전략의 표기 이름(예: "S7"). 플레이 로그 헤더에 쓴다. */
    public String strategyName() {
        return strategy.displayName();
    }

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        think(strategy.thinkDelay());
        return strategy.decideSplit(context);
    }

    @Override
    public int decideChoice(ChoiceContext context) {
        think(strategy.thinkDelay());
        return strategy.decideChoice(context);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount, HelperDraftContext context) {
        think(strategy.thinkDelay());
        return strategy.decideHelpers(options, chooseCount, context);
    }

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<Player> members) {
        think(strategy.thinkDelay());
        List<List<Card>> memberHoldings = new ArrayList<>();
        for (Player member : members) {
            memberHoldings.add(new ArrayList<>(member.holdings()));
        }
        return strategy.decideTeamDistribution(acquired, memberHoldings);
    }

    @Override
    public void beginCashIn(CashInContext snapshot, int opponentCoins, CashSink sink) {
        // 봇은 자기 가상 스레드에서 계획을 세워 페이스대로 하나씩 제출하고 마지막에 패스한다.
        // 게임 스레드를 막지 않으므로 think/페이싱을 봇이 직접 소유한다(엔진은 큐만 기다린다).
        // 환금 행동 사이는 일반 의사결정보다 짧은 별도 페이싱을 쓴다.
        Thread.ofVirtual().name("bot-cash-" + name()).start(() -> {
            List<CashInAction> plan = strategy.planCashIn(snapshot, opponentCoins);
            for (CashInAction action : plan) {
                think(strategy.cashInThinkDelay());
                sink.submit(action);
            }
            think(strategy.cashInThinkDelay());
            sink.pass();
        });
    }

    @Override
    public boolean isBot() {
        return true;
    }

    private void think(ThinkDelay delay) {
        if (!paced || delay.maxMillis() <= 0) {
            return;
        }
        int millis = delay.minMillis() == delay.maxMillis()
                ? delay.minMillis()
                : ThreadLocalRandom.current().nextInt(delay.minMillis(), delay.maxMillis() + 1);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
