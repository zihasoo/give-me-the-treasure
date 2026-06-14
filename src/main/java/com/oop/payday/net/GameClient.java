package com.oop.payday.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.function.Function;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.game.GameListener;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;

import javafx.application.Platform;

/**
 * 클라이언트 측 TCP 연결.
 * 연결·핸드셰이크 후 리더 스레드를 시작하며,
 * 수신한 {@link NetMessage.Envelope} 를 미러에 적용하고 컨트롤러 콜백을 dispatching 한다.
 */
public final class GameClient implements Closeable {

    /** 접속 시도 제한 시간 — OS 기본(윈도우 ~21초)을 기다리지 않게 한다. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private Socket socket;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

    /** {@link #close} 로 의도적으로 닫혔는지 — 이후 리더 스레드의 끊김 콜백을 억제한다. */
    private volatile boolean closed;

    /**
     * 현재 게임 세대. 핸드셰이크/재시작이 정하며, 다른 세대의 {@link NetMessage.Envelope} 는
     * 버린다(재시작 직후 도착하는 이전 판의 늦은 이벤트 차단). JavaFX 스레드에서만 접근.
     */
    private int currentEpoch;

    /**
     * 가장 최근 수신한 입력 요청(분할/선택/도우미/환금)의 식별자.
     * 응답 전송 시 {@link NetworkInputGateway} 가 그대로 echo 해 호스트가 stale 응답을 걸러낸다.
     * reader 스레드(JavaFX)에서만 쓰고, 응답은 JavaFX 스레드에서 읽으므로 volatile 로 충분.
     */
    private volatile long currentRequestId;

    /**
     * 현재 따르는 미러. 재시작({@link NetMessage.Restart}) 시 새 미러로 교체되며,
     * 이후 수신한 {@link NetMessage.Envelope} 는 교체된 미러에 적용된다.
     * reader 스레드가 enqueue 한 runLater 들은 JavaFX 스레드에서 순서대로 실행되므로,
     * 재시작 처리가 후속 Envelope 적용보다 먼저 끝난다.
     */
    private volatile ClientMirror mirror;

    /** 게임 단계 리스너/재시작 콜백 — {@link #enterGame} 에서 설정. */
    private volatile GameListener gameListener;
    private volatile Function<NetMessage.Restart, ClientMirror> onRestart;
    /** 현재 단계(대기실→게임)에 맞는 연결 종료 콜백. */
    private volatile Runnable onDisconnect;
    private volatile LobbyHandler lobbyHandler;

    /**
     * 대기실 단계의 호스트→클라이언트 메시지를 처리하는 콜백.
     * 모두 JavaFX 스레드에서 호출된다.
     */
    public interface LobbyHandler {
        /** 대기실 상태 갱신 수신. */
        void onLobbyState(NetMessage.LobbyState state);
        /** 호스트가 게임을 시작 — 핸드셰이크 수신. 구현체는 미러를 만들고 {@link GameClient#enterGame} 를 호출한다. */
        void onGameStart(NetMessage.Handshake handshake);
        /** 호스트가 대기실을 닫음. */
        void onLobbyClosed(String reason);
    }

    /** 호스트에 연결하고 스트림을 연다(블로킹, 최대 {@link #CONNECT_TIMEOUT_MS}). 핸드셰이크는 대기실 단계 이후에 받는다. */
    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);   // 턴제 소량 메시지 — Nagle 지연 제거
        socket.setKeepAlive(true);    // 무응답 피어(전원 차단 등) 감지 보조
        oos = new ObjectOutputStream(socket.getOutputStream());
        oos.flush();
        ois = new ObjectInputStream(socket.getInputStream());
        ois.setObjectInputFilter(WireCodec.WIRE_FILTER);
    }

    /** 가장 최근 입력 요청의 식별자. 응답에 함께 실어 호스트가 stale 응답을 걸러내게 한다. */
    public long currentRequestId() {
        return currentRequestId;
    }

    /** 결정 메시지를 호스트로 전송한다(스레드 안전). */
    public void send(NetMessage msg) throws IOException {
        synchronized (oos) {
            oos.writeObject(msg);
            oos.reset();
            oos.flush();
        }
    }

    /**
     * 대기실 단계 수신 루프를 시작한다. 단일 reader 스레드가 메시지 종류에 따라 분기한다:
     * <ul>
     *   <li>{@link NetMessage.LobbyState}/{@link NetMessage.Handshake}/{@link NetMessage.LobbyClosed}
     *       → {@link LobbyHandler}
     *   <li>(게임 진입 후) {@link NetMessage.Envelope}/{@link NetMessage.Restart} → 미러 적용·이벤트 dispatch
     * </ul>
     * 게임 진입은 {@link #enterGame} 가 미러/리스너/재시작 콜백을 설정하며, reader 스레드는 그대로 재사용한다.
     */
    public void startLobby(LobbyHandler handler, Runnable onDisconnect) {
        this.lobbyHandler = handler;
        this.onDisconnect = onDisconnect;
        Thread t = new Thread(this::readLoop, "net-client-reader");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 게임 단계로 전환한다(핸드셰이크 수신 후, JavaFX 스레드에서 호출).
     * 이후 수신되는 {@link NetMessage.Envelope}/{@link NetMessage.Restart} 가 이 미러/리스너로 처리된다.
     *
     * @param mirror       초기 미러 상태 저장소
     * @param epoch        핸드셰이크가 정한 게임 세대 — 다른 세대의 봉투는 버린다
     * @param listener     컨트롤러 (GameListener)
     * @param onDisconnect 게임 단계 연결 종료 시 호출(Platform.runLater 로 감쌈)
     * @param onRestart    {@link NetMessage.Restart} 수신 시 새 미러를 만들어 돌려주는 콜백(JavaFX 스레드)
     */
    public void enterGame(ClientMirror mirror, int epoch, GameListener listener,
            Runnable onDisconnect, Function<NetMessage.Restart, ClientMirror> onRestart) {
        this.mirror = mirror;
        this.currentEpoch = epoch;
        this.gameListener = listener;
        this.onRestart = onRestart;
        this.onDisconnect = onDisconnect;
        this.lobbyHandler = null; // 게임 진입 후 들어오는 늦은 대기실 메시지는 무시한다.
    }

    private void readLoop() {
        try {
            while (true) {
                NetMessage msg = (NetMessage) ois.readObject();
                switch (msg) {
                    case NetMessage.LobbyState ls -> {
                        LobbyHandler h = lobbyHandler;
                        if (h != null) Platform.runLater(() -> h.onLobbyState(ls));
                    }
                    case NetMessage.Handshake hs -> {
                        LobbyHandler h = lobbyHandler;
                        if (h != null) Platform.runLater(() -> h.onGameStart(hs));
                    }
                    case NetMessage.LobbyClosed lc -> {
                        LobbyHandler h = lobbyHandler;
                        if (h != null) Platform.runLater(() -> h.onLobbyClosed(lc.reason()));
                    }
                    case NetMessage.Restart restart -> {
                        // 재시작: 컨트롤러가 새 미러를 만들고 보드를 리셋한다. 이후 Envelope 는 새 미러에 적용.
                        Platform.runLater(() -> {
                            Function<NetMessage.Restart, ClientMirror> r = this.onRestart;
                            if (r != null) {
                                this.mirror = r.apply(restart);
                                this.currentEpoch = restart.epoch();
                            }
                        });
                    }
                    case NetMessage.Envelope env -> {
                        // 상태 적용 → 이벤트 dispatch 를 같은 runLater 로 묶어 순서와 스레드를 보장한다.
                        Platform.runLater(() -> {
                            ClientMirror current = this.mirror;
                            GameListener l = this.gameListener;
                            if (current == null || l == null) return; // 게임 진입 전 — 무시
                            if (env.epoch() != currentEpoch) return;  // 이전 판의 늦은 이벤트 — 무시
                            current.applyState(env.state());
                            dispatchEvent(env.event(), current, l);
                        });
                    }
                    default -> {} // 클라이언트→호스트 결정 메시지 등 무시
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            // 연결 종료
        } finally {
            if (!closed) {  // 의도적으로 닫은 경우(나가기 등)에는 끊김 통지를 보내지 않는다.
                Runnable d = this.onDisconnect;
                if (d != null) Platform.runLater(d);
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (socket != null && !socket.isClosed()) socket.close();
    }

    // ── 이벤트 디스패치 ──────────────────────────────────────────────

    /**
     * 미러를 참조해 {@link GameListener} 콜백을 호출한다.
     * <b>이미 JavaFX Application Thread 에서(startReaderLoop 의 runLater 안에서) 실행되므로</b>
     * 여기서 추가로 {@link Platform#runLater} 로 감싸지 않는다.
     */
    private void dispatchEvent(GameEvent event, ClientMirror mirror, GameListener listener) {
        switch (event) {
            case GameEvent.PhaseChanged e ->
                    listener.onPhaseChanged(e.phase(), e.round(), mirror.teamById(e.splitTeamId()));

            case GameEvent.GameSetup e ->
                    listener.onGameSetup(e.playerIds().stream().map(mirror::playerById).toList());

            case GameEvent.PlayerSetup e ->
                    listener.onPlayerSetup(mirror.playerById(e.playerId()));

            case GameEvent.HandDealt e -> {
                List<Card> hand = e.hand().stream().map(mirror::getOrCreateCard).toList();
                listener.onHandDealt(mirror.playerById(e.splitterId()), hand);
            }

            case GameEvent.ChoiceReady e ->
                    listener.onChoiceReady(new GameListener.BundlePair(
                            cards(e.visible0(), mirror), e.faceDown0(),
                            cards(e.visible1(), mirror), e.faceDown1()));

            case GameEvent.Distributed e ->
                    listener.onDistributed(
                            e.chosenIndex(),
                            mirror.teamById(e.chooseTeamId()),
                            cards(e.chooseCards(), mirror),
                            mirror.teamById(e.splitTeamId()),
                            cards(e.splitCards(), mirror));

            case GameEvent.CashIn e -> {
                TreasureSet set = new TreasureSet(
                        cards(e.set().cards(), mirror), e.set().type(), e.set().coin());
                listener.onCashIn(mirror.playerById(e.playerId()), set);
            }

            case GameEvent.CashTurn e -> {
                currentRequestId = e.requestId();
                CashInContextDto ctx = e.context();
                CashInContext cashCtx = new CashInContext(
                        cards(ctx.holdings(), mirror),
                        helpers(ctx.helpers(), mirror),
                        helpers(ctx.usedHelpers(), mirror),
                        cards(ctx.discardPile(), mirror),
                        ctx.teamCoins(),
                        ctx.holdLimit(),
                        ctx.winningCoins());
                listener.onCashTurn(mirror.playerById(e.playerId()), cashCtx);
            }

            case GameEvent.CashDone e ->
                    listener.onCashDone(mirror.playerById(e.playerId()));

            case GameEvent.Discard e ->
                    listener.onDiscard(mirror.playerById(e.playerId()),
                            mirror.getOrCreateCard(e.card()));

            case GameEvent.HelperUsed e ->
                    listener.onHelperUsed(
                            mirror.playerById(e.playerId()),
                            mirror.getOrCreateHelper(e.helper()),
                            e.message(),
                            cards(e.drawn(), mirror),
                            cards(e.discarded(), mirror));

            case GameEvent.ForcedDiscard e ->
                    listener.onForcedDiscard(mirror.playerById(e.playerId()),
                            cards(e.cards(), mirror));

            case GameEvent.CoinsChanged e ->
                    listener.onCoinsChanged(mirror.teamById(e.teamId()), e.delta());

            case GameEvent.RoundEnd e ->
                    listener.onRoundEnd(e.round());

            case GameEvent.GameOver e ->
                    listener.onGameOver(mirror.teamById(e.winnerTeamId()));

            case GameEvent.Message e ->
                    listener.onMessage(e.text());

            case GameEvent.StealActivated e -> {
                Card drawn = e.drawnCard() != null ? mirror.getOrCreateCard(e.drawnCard()) : null;
                listener.onStealActivated(mirror.playerById(e.playerId()), drawn);
            }

            case GameEvent.RequestSplit e -> {
                currentRequestId = e.requestId();
                List<Card> hand = e.hand().stream().map(mirror::getOrCreateCard).toList();
                listener.onRequestSplit(mirror.myPlayer(), hand);
            }

            case GameEvent.RequestChoice e -> {
                currentRequestId = e.requestId();
                ChoiceView view = new ChoiceView(List.of(
                        new BundleView(cards(e.visible0(), mirror), e.faceDown0()),
                        new BundleView(cards(e.visible1(), mirror), e.faceDown1())));
                listener.onRequestChoice(mirror.myPlayer(), view);
            }

            case GameEvent.RequestHelpers e -> {
                currentRequestId = e.requestId();
                List<HelperCard> opts = e.options().stream()
                        .<HelperCard>map(h -> mirror.getOrCreateHelper(h))
                        .toList();
                listener.onRequestHelpers(mirror.playerById(e.playerId()), opts, e.chooseCount());
            }

            case GameEvent.RequestTeamDistribution e -> {
                currentRequestId = e.requestId();
                List<Card> acquired = e.acquired().stream().map(mirror::getOrCreateCard).toList();
                listener.onRequestTeamDistribution(
                        mirror.playerById(e.leaderId()), mirror.teamById(e.teamId()), acquired);
            }

            case GameEvent.HelperSelectionNotified e -> {
                List<HelperCard> opts = e.options().stream()
                        .<HelperCard>map(h -> mirror.getOrCreateHelper(h))
                        .toList();
                // currentRequestId 는 갱신하지 않음 — 팀원은 응답하지 않는다
                listener.onRequestHelpers(mirror.playerById(e.leaderId()), opts, 0);
            }

            case GameEvent.DistributionSelectionNotified e -> {
                List<Card> acquired = e.acquired().stream().map(mirror::getOrCreateCard).toList();
                // currentRequestId 는 갱신하지 않음 — 팀원은 분배에 응답하지 않는다(읽기 전용).
                listener.onRequestTeamDistribution(
                        mirror.playerById(e.leaderId()), mirror.teamById(e.teamId()), acquired);
            }

            case GameEvent.TeamDistributionPreview e ->
                    listener.onTeamDistributionPreview(e.assignment());

            case GameEvent.HelperSelectionPreview e ->
                    listener.onHelperSelectionPreview(e.roles());

            case GameEvent.TeamDistributionDone e ->
                    listener.onTeamDistributionDone(e.leaderId());
        }
    }

    private static List<Card> cards(List<CardDto> dtos, ClientMirror mirror) {
        return dtos.stream().map(mirror::getOrCreateCard).toList();
    }

    private static List<HelperCard> helpers(List<HelperDto> dtos, ClientMirror mirror) {
        return dtos.stream().<HelperCard>map(h -> mirror.getOrCreateHelper(h)).toList();
    }
}
