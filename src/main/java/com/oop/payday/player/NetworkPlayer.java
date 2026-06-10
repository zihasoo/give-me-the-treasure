package com.oop.payday.player;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.CashSink;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.game.NetworkDisconnectedException;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 호스트 측 원격 클라이언트 대리자. {@link HumanPlayer}와 대칭 구조로,
 * 의사결정 요청이 오면 블록하고, 네트워크 리더 스레드가 결정을 받아와 unblock한다.
 *
 * <p>게임 스레드는 {@code decideXxx} 에서 블록한다.
 * {@link com.oop.payday.net.GameServer} 리더 스레드가 클라이언트 메시지를 파싱한 뒤
 * {@code provideXxx} 로 결정을 전달해 게임을 진행시킨다.
 *
 * <p><b>요청-응답 상관관계:</b> 호스트가 보내는 입력 요청마다 {@link #nextRequestId} 로 id 와 종류를 매기고,
 * 클라이언트 응답이 echo 한 id 와 응답 종류가 현재 대기 중인 요청과 일치할 때만({@link #consumeRequest})
 * 처리해 stale·중복·wrong-type 응답을 버린다.
 *
 * <p><b>연결 해제:</b> {@link #abort} 가 대기 중인 결정 채널을 풀어 {@link NetworkDisconnectedException}
 * 으로 게임 스레드를 깨운다. 환금 인박스 대기는 {@code Game.abort()} 가 별도로 푼다.
 */
public final class NetworkPlayer extends Player {

    private final DecisionChannel<SplitDecision> splitChannel = new DecisionChannel<>();
    private final DecisionChannel<Integer> choiceChannel = new DecisionChannel<>();
    private final DecisionChannel<List<HelperCard>> helperChannel = new DecisionChannel<>();
    private final DecisionChannel<TeamDistribution> distributionChannel = new DecisionChannel<>();

    /** id 복원용 — decideTeamDistribution 에서 저장, 리더 스레드가 분배 결정 파싱 시 사용. */
    public volatile List<Card> currentAcquired;

    /** 환금 제출 창구. beginCashIn 에서 저장, 네트워크 리더가 submitCash/passCash 로 사용. */
    private volatile CashSink cashSink;

    /** id 복원용 — decideSplit 에서 저장, 리더 스레드가 분할 결정 파싱 시 사용. */
    public volatile List<Card> currentHand;
    /** id 복원용 — decideHelpers 에서 저장. */
    public volatile List<HelperCard> currentHelperOptions;

    public enum RequestKind {
        SPLIT,
        CHOICE,
        HELPERS,
        DISTRIBUTION,
        CASH
    }

    private record ActiveRequest(long id, RequestKind kind) {}

    // 요청-응답 상관관계용 식별자. 전역(static) 카운터라 재시작으로 만들어진 새 인스턴스의 id 가
    // 이전 판의 id 와 절대 겹치지 않는다 → 재시작 직전에 전송 중이던 응답이 새 판 요청에 수용될 수 없다.
    private static final AtomicLong REQUEST_SEQ = new AtomicLong();
    private final AtomicReference<ActiveRequest> activeRequest = new AtomicReference<>();

    public NetworkPlayer(String name) {
        super(name);
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        this.currentHand = hand;
        return splitChannel.take();
    }

    @Override
    public int decideChoice(ChoiceView view) {
        return choiceChannel.take();
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        this.currentHelperOptions = options;
        return helperChannel.take();
    }

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<Player> members) {
        this.currentAcquired = acquired;
        return distributionChannel.take();
    }

    @Override
    public void beginCashIn(CashInContext snapshot, CashSink sink) {
        this.cashSink = sink;
    }

    @Override
    public boolean isBot() {
        return false;
    }

    // --- 요청-응답 상관관계 (호스트 브로드캐스터/리더 스레드가 호출) ---

    /** 새 입력 요청 id 를 발급하고 현재 대기 요청으로 등록한다. */
    public long nextRequestId(RequestKind kind) {
        long id = REQUEST_SEQ.incrementAndGet();
        activeRequest.set(new ActiveRequest(id, kind));
        return id;
    }

    /**
     * 응답 id 와 종류가 현재 대기 중인 요청과 일치하면 소거하고 {@code true},
     * 아니면(stale·중복·wrong-type) {@code false}.
     * wrong-type 응답은 active request 를 소모하지 않아 뒤이어 오는 올바른 응답을 막지 않는다.
     */
    public boolean consumeRequest(long requestId, RequestKind kind) {
        ActiveRequest current = activeRequest.get();
        if (current == null || current.id() != requestId || current.kind() != kind) {
            return false;
        }
        return activeRequest.compareAndSet(current, null);
    }

    // --- 네트워크 리더 스레드가 호출 ---

    public void provideSplit(SplitDecision decision) {
        splitChannel.put(decision);
    }

    public void provideChoice(int index) {
        choiceChannel.put(index);
    }

    public void provideHelpers(List<HelperCard> helpers) {
        helperChannel.put(helpers);
    }

    public void provideDistribution(TeamDistribution distribution) {
        distributionChannel.put(distribution);
    }

    public void submitCash(CashInAction action) {
        CashSink sink = cashSink;
        if (sink != null) {
            sink.submit(action);
        }
    }

    public void passCash() {
        CashSink sink = cashSink;
        if (sink != null) {
            sink.pass();
        }
    }

    /**
     * 연결 해제 시 호출: 대기 중인 결정 채널을 모두 풀어 게임 스레드를 깨운다.
     * 깨어난 {@code take()} 는 {@link NetworkDisconnectedException} 을 던진다.
     * 한번 abort 된 채널은 이후 {@code take()} 도 즉시 예외를 던지므로 중복 호출에도 안전하다.
     */
    public void abort() {
        splitChannel.abort();
        choiceChannel.abort();
        helperChannel.abort();
        distributionChannel.abort();
    }

    /**
     * 결정 한 건을 주고받는 1슬롯 메일박스.
     * <ul>
     *   <li>{@code put} 은 절대 블록하지 않는다 → stale·중복 응답을 흘려도 리더 스레드가 멈추지 않는다.
     *   <li>{@code abort} 는 대기 중인 {@code take} 를 즉시 깨워 {@link NetworkDisconnectedException} 을 던진다.
     * </ul>
     */
    private static final class DecisionChannel<T> {
        private final Object lock = new Object();
        private T value;
        private boolean hasValue;
        private boolean aborted;

        T take() {
            synchronized (lock) {
                while (!hasValue && !aborted) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NetworkDisconnectedException();
                    }
                }
                if (aborted) {
                    throw new NetworkDisconnectedException();
                }
                hasValue = false;
                T v = value;
                value = null;
                return v;
            }
        }

        void put(T v) {
            synchronized (lock) {
                value = v;
                hasValue = true;
                lock.notifyAll();
            }
        }

        void abort() {
            synchronized (lock) {
                aborted = true;
                lock.notifyAll();
            }
        }
    }
}
