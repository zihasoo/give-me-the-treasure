package com.oop.payday.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

    private Socket socket;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

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

    /** connect 결과 핸드셰이크를 반환한다(블로킹). */
    public NetMessage.Handshake connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        oos = new ObjectOutputStream(socket.getOutputStream());
        oos.flush();
        ois = new ObjectInputStream(socket.getInputStream());
        try {
            return (NetMessage.Handshake) ois.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException("핸드셰이크 역직렬화 실패", e);
        }
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
     * 수신 루프 스레드를 시작한다.
     * 수신된 Envelope 마다 {@code mirror.applyState} 와 이벤트 dispatch 를
     * <b>하나의 {@link Platform#runLater}</b> 안에서 순차 실행한다.
     * 미러 모델(Team/Player/카드/도우미)을 만지는 주체를 JavaFX Application Thread 로 단일화해,
     * reader 스레드와 UI 스레드가 같은 객체를 동시에 건드리는 경쟁을 없앤다.
     *
     * @param mirror       초기 미러 상태 저장소
     * @param listener     컨트롤러 (GameListener)
     * @param onDisconnect 연결 종료 시 호출(Platform.runLater 로 감쌈)
     * @param onRestart    {@link NetMessage.Restart} 수신 시 새 미러를 만들어 돌려주는 콜백
     *                     (JavaFX 스레드에서 호출됨). 반환된 미러가 이후 Envelope 적용 대상이 된다.
     */
    public void startReaderLoop(ClientMirror mirror, GameListener listener,
            Runnable onDisconnect, Function<NetMessage.Restart, ClientMirror> onRestart) {
        this.mirror = mirror;
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    NetMessage msg = (NetMessage) ois.readObject();
                    if (msg instanceof NetMessage.Restart restart) {
                        // 재시작: 컨트롤러가 새 미러를 만들고 보드를 리셋한다. 이후 Envelope 는 새 미러에 적용.
                        Platform.runLater(() -> this.mirror = onRestart.apply(restart));
                    } else if (msg instanceof NetMessage.Envelope env) {
                        // 상태 적용 → 이벤트 dispatch 를 같은 runLater 로 묶어 순서와 스레드를 보장한다.
                        Platform.runLater(() -> {
                            ClientMirror current = this.mirror;
                            current.applyState(env.state());
                            dispatchEvent(env.event(), current, listener);
                        });
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                // 연결 종료
            } finally {
                if (onDisconnect != null) {
                    Platform.runLater(onDisconnect);
                }
            }
        }, "net-client-reader");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() throws IOException {
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
                        ctx.holdLimit());
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
        }
    }

    private static List<Card> cards(List<CardDto> dtos, ClientMirror mirror) {
        return dtos.stream().map(mirror::getOrCreateCard).toList();
    }

    private static List<HelperCard> helpers(List<HelperDto> dtos, ClientMirror mirror) {
        return dtos.stream().<HelperCard>map(h -> mirror.getOrCreateHelper(h)).toList();
    }
}
