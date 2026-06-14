package com.oop.payday.player;

import java.util.List;
import java.util.concurrent.SynchronousQueue;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.CashSink;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.game.GameAbortedException;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 사람 플레이어. 의사결정은 UI 스레드가 채워 넣는다.
 *
 * <p>게임 루프는 별도 스레드에서 돌며 {@code decideXxx} 에서 블록한다.
 * UI(M3)는 사용자가 행동을 마치면 {@code provideXxx} 로 결정을 전달해 게임을 진행시킨다.
 * {@link SynchronousQueue} 로 한 번에 하나의 결정만 주고받는다.
 */
public final class HumanPlayer extends Player {

    private final SynchronousQueue<SplitDecision> splitChannel = new SynchronousQueue<>();
    private final SynchronousQueue<Integer> choiceChannel = new SynchronousQueue<>();
    private final SynchronousQueue<List<HelperCard>> helperChannel = new SynchronousQueue<>();
    private final SynchronousQueue<TeamDistribution> distributionChannel = new SynchronousQueue<>();

    // 환금 제출 창구. 게임 스레드가 beginCashIn 에서 설정하고, UI 스레드가 submitCash/passCash 로 사용.
    private volatile CashSink cashSink;

    public HumanPlayer(String name) {
        super(name);
    }

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        return take(splitChannel);
    }

    @Override
    public int decideChoice(ChoiceContext context) {
        return take(choiceChannel);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        return take(helperChannel);
    }

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<Player> members) {
        return take(distributionChannel);
    }

    @Override
    public void beginCashIn(CashInContext snapshot, int opponentCoins, CashSink sink) {
        // 사람은 즉시 제출하지 않는다. sink를 보관해 두고 UI 입력이 올 때 submitCash/passCash로 제출한다.
        // opponentCoins 는 봇 전용 신호라 사람은 무시한다.
        this.cashSink = sink;
    }

    @Override
    public boolean isBot() {
        return false;
    }

    // --- UI 스레드가 호출 (M3) ---

    public void provideSplit(SplitDecision decision) {
        put(splitChannel, decision);
    }

    public void provideChoice(int index) {
        put(choiceChannel, index);
    }

    public void provideHelpers(List<HelperCard> helpers) {
        put(helperChannel, helpers);
    }

    public void provideDistribution(TeamDistribution distribution) {
        put(distributionChannel, distribution);
    }

    /** UI 스레드: 환금 행동 한 건을 제출한다. */
    public void submitCash(CashInAction action) {
        cashSink.submit(action);
    }

    /** UI 스레드: 환금 턴 종료를 제출한다. */
    public void passCash() {
        cashSink.pass();
    }

    private static <T> T take(SynchronousQueue<T> channel) {
        try {
            return channel.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 재시작·나가기로 게임 스레드가 인터럽트됨 → 게임 루프(play)가 잡아 조용히 종료한다.
            throw new GameAbortedException();
        }
    }

    private static <T> void put(SynchronousQueue<T> channel, T value) {
        try {
            channel.put(value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("의사결정 전달 중 인터럽트됨", e);
        }
    }
}
