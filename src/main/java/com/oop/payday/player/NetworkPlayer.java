package com.oop.payday.player;

import java.util.List;
import java.util.concurrent.SynchronousQueue;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.CashSink;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 호스트 측 원격 클라이언트 대리자. {@link HumanPlayer}와 대칭 구조로,
 * 의사결정 요청이 오면 블록하고, 네트워크 리더 스레드가 결정을 받아와 unblock한다.
 *
 * <p>게임 스레드는 {@code decideXxx} 에서 블록한다.
 * {@link com.oop.payday.net.GameServer} 리더 스레드가 클라이언트 메시지를 파싱한 뒤
 * {@code provideXxx} 로 결정을 전달해 게임을 진행시킨다.
 */
public final class NetworkPlayer extends Player {

    private final SynchronousQueue<SplitDecision> splitChannel = new SynchronousQueue<>();
    private final SynchronousQueue<Integer> choiceChannel = new SynchronousQueue<>();
    private final SynchronousQueue<List<HelperCard>> helperChannel = new SynchronousQueue<>();

    /** 환금 제출 창구. beginCashIn 에서 저장, 네트워크 리더가 submitCash/passCash 로 사용. */
    private volatile CashSink cashSink;

    /** id 복원용 — decideSplit 에서 저장, 리더 스레드가 분할 결정 파싱 시 사용. */
    public volatile List<Card> currentHand;
    /** id 복원용 — decideHelpers 에서 저장. */
    public volatile List<HelperCard> currentHelperOptions;

    public NetworkPlayer(String name) {
        super(name);
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        this.currentHand = hand;
        return take(splitChannel);
    }

    @Override
    public int decideChoice(ChoiceView view) {
        return take(choiceChannel);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        this.currentHelperOptions = options;
        return take(helperChannel);
    }

    @Override
    public void beginCashIn(CashInContext snapshot, CashSink sink) {
        this.cashSink = sink;
    }

    @Override
    public boolean isBot() {
        return false;
    }

    // --- 네트워크 리더 스레드가 호출 ---

    public void provideSplit(SplitDecision decision) {
        put(splitChannel, decision);
    }

    public void provideChoice(int index) {
        put(choiceChannel, index);
    }

    public void provideHelpers(List<HelperCard> helpers) {
        put(helperChannel, helpers);
    }

    public void submitCash(CashInAction action) {
        cashSink.submit(action);
    }

    public void passCash() {
        cashSink.pass();
    }

    private static <T> T take(SynchronousQueue<T> channel) {
        try {
            return channel.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NetworkPlayer 대기 중 인터럽트됨", e);
        }
    }

    private static <T> void put(SynchronousQueue<T> channel, T value) {
        try {
            channel.put(value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NetworkPlayer 전달 중 인터럽트됨", e);
        }
    }
}
