package com.oop.payday.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.List;

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
     * 수신된 Envelope 마다 {@code mirror.applyState} → {@code Platform.runLater(dispatch)} 를 실행한다.
     *
     * @param mirror       미러 상태 저장소
     * @param listener     컨트롤러 (GameListener)
     * @param onDisconnect 연결 종료 시 호출(Platform.runLater 로 감쌈)
     */
    public void startReaderLoop(ClientMirror mirror, GameListener listener,
            Runnable onDisconnect) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    NetMessage.Envelope env = (NetMessage.Envelope) ois.readObject();
                    // 상태 갱신은 reader 스레드에서 먼저, dispatch 는 JavaFX 스레드에서
                    mirror.applyState(env.state());
                    dispatchEvent(env.event(), mirror, listener);
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

    private void dispatchEvent(GameEvent event, ClientMirror mirror, GameListener listener) {
        switch (event) {
            case GameEvent.PhaseChanged e -> Platform.runLater(() ->
                    listener.onPhaseChanged(e.phase(), e.round(), mirror.teamById(e.splitTeamId())));

            case GameEvent.GameSetup e -> Platform.runLater(() ->
                    listener.onGameSetup(e.playerIds().stream().map(mirror::playerById).toList()));

            case GameEvent.PlayerSetup e -> Platform.runLater(() ->
                    listener.onPlayerSetup(mirror.playerById(e.playerId())));

            case GameEvent.HandDealt e -> Platform.runLater(() -> {
                List<Card> hand = e.hand().stream().map(mirror::getOrCreateCard).toList();
                listener.onHandDealt(mirror.playerById(e.splitterId()), hand);
            });

            case GameEvent.ChoiceReady e -> Platform.runLater(() ->
                    listener.onChoiceReady(new GameListener.BundlePair(
                            cards(e.visible0(), mirror), e.faceDown0(),
                            cards(e.visible1(), mirror), e.faceDown1())));

            case GameEvent.Distributed e -> Platform.runLater(() ->
                    listener.onDistributed(
                            e.chosenIndex(),
                            mirror.teamById(e.chooseTeamId()),
                            cards(e.chooseCards(), mirror),
                            mirror.teamById(e.splitTeamId()),
                            cards(e.splitCards(), mirror)));

            case GameEvent.CashIn e -> Platform.runLater(() -> {
                TreasureSet set = new TreasureSet(
                        cards(e.set().cards(), mirror), e.set().type(), e.set().coin());
                listener.onCashIn(mirror.playerById(e.playerId()), set);
            });

            case GameEvent.CashTurn e -> Platform.runLater(() -> {
                CashInContextDto ctx = e.context();
                CashInContext cashCtx = new CashInContext(
                        cards(ctx.holdings(), mirror),
                        helpers(ctx.helpers(), mirror),
                        helpers(ctx.usedHelpers(), mirror),
                        cards(ctx.discardPile(), mirror),
                        ctx.teamCoins(),
                        ctx.holdLimit());
                listener.onCashTurn(mirror.playerById(e.playerId()), cashCtx);
            });

            case GameEvent.CashDone e -> Platform.runLater(() ->
                    listener.onCashDone(mirror.playerById(e.playerId())));

            case GameEvent.Discard e -> Platform.runLater(() ->
                    listener.onDiscard(mirror.playerById(e.playerId()),
                            mirror.getOrCreateCard(e.card())));

            case GameEvent.HelperUsed e -> Platform.runLater(() ->
                    listener.onHelperUsed(
                            mirror.playerById(e.playerId()),
                            mirror.getOrCreateHelper(e.helper()),
                            e.message(),
                            cards(e.drawn(), mirror),
                            cards(e.discarded(), mirror)));

            case GameEvent.ForcedDiscard e -> Platform.runLater(() ->
                    listener.onForcedDiscard(mirror.playerById(e.playerId()),
                            cards(e.cards(), mirror)));

            case GameEvent.CoinsChanged e -> Platform.runLater(() ->
                    listener.onCoinsChanged(mirror.teamById(e.teamId()), e.delta()));

            case GameEvent.RoundEnd e -> Platform.runLater(() ->
                    listener.onRoundEnd(e.round()));

            case GameEvent.GameOver e -> Platform.runLater(() ->
                    listener.onGameOver(mirror.teamById(e.winnerTeamId())));

            case GameEvent.Message e -> Platform.runLater(() ->
                    listener.onMessage(e.text()));

            case GameEvent.StealActivated e -> Platform.runLater(() -> {
                Card drawn = e.drawnCard() != null ? mirror.getOrCreateCard(e.drawnCard()) : null;
                listener.onStealActivated(mirror.playerById(e.playerId()), drawn);
            });

            case GameEvent.RequestSplit e -> Platform.runLater(() -> {
                List<Card> hand = e.hand().stream().map(mirror::getOrCreateCard).toList();
                listener.onRequestSplit(mirror.myPlayer(), hand);
            });

            case GameEvent.RequestChoice e -> Platform.runLater(() -> {
                ChoiceView view = new ChoiceView(List.of(
                        new BundleView(cards(e.visible0(), mirror), e.faceDown0()),
                        new BundleView(cards(e.visible1(), mirror), e.faceDown1())));
                listener.onRequestChoice(mirror.myPlayer(), view);
            });

            case GameEvent.RequestHelpers e -> Platform.runLater(() -> {
                List<HelperCard> opts = e.options().stream()
                        .<HelperCard>map(h -> mirror.getOrCreateHelper(h))
                        .toList();
                listener.onRequestHelpers(mirror.playerById(e.playerId()), opts, e.chooseCount());
            });
        }
    }

    private static List<Card> cards(List<CardDto> dtos, ClientMirror mirror) {
        return dtos.stream().map(mirror::getOrCreateCard).toList();
    }

    private static List<HelperCard> helpers(List<HelperDto> dtos, ClientMirror mirror) {
        return dtos.stream().<HelperCard>map(h -> mirror.getOrCreateHelper(h)).toList();
    }
}
