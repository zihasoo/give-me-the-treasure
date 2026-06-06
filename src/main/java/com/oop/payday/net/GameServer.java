package com.oop.payday.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.game.GameConfig;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.player.NetworkPlayer.RequestKind;
import com.oop.payday.player.Player;

/**
 * 호스트 측 TCP 서버.
 * <ol>
 *   <li>클라이언트 연결 수락 + 핸드셰이크 전송
 *   <li>클라이언트 결정 메시지 수신 → id 복원 → {@link NetworkPlayer} 로 라우팅
 * </ol>
 * 리더 스레드는 {@link #startReaderLoop} 로 시작한다.
 */
public final class GameServer implements Closeable {

    public static final int DEFAULT_PORT = 23456;

    private final ServerSocket serverSocket;
    private Socket clientSocket;
    private ObjectOutputStream oos;
    private ObjectInputStream ois;

    private NetworkPlayer networkPlayer;
    private List<Player> allPlayers;

    public GameServer() throws IOException {
        serverSocket = new ServerSocket(DEFAULT_PORT);
    }

    /** 클라이언트 연결을 수락한다(블로킹). accept 후 serverSocket 은 닫는다. */
    public void acceptClient() throws IOException {
        clientSocket = serverSocket.accept();
        serverSocket.close();
        oos = new ObjectOutputStream(clientSocket.getOutputStream());
        oos.flush();
        ois = new ObjectInputStream(clientSocket.getInputStream());
    }

    /** 브로드캐스터가 직접 쓸 수 있도록 OOS 를 노출한다(acceptClient 이후 호출). */
    public ObjectOutputStream outputStream() {
        return oos;
    }

    /** 핸드셰이크를 전송한다(acceptClient 이후 호출). */
    public void sendHandshake(GameConfig config, int clientTeamId, PublicBoardState state) throws IOException {
        synchronized (oos) {
            oos.writeObject(new NetMessage.Handshake(
                    config.winningCoins(), config.leaderEffectsEnabled(),
                    clientTeamId, state));
            oos.reset();
            oos.flush();
        }
    }

    /**
     * 네트워크 리더 스레드를 시작한다.
     * 클라이언트가 보내는 결정 메시지를 읽어 {@link NetworkPlayer} 로 라우팅한다.
     * 연결이 끊기면 {@link NetworkPlayer} 채널을 인터럽트해 게임 루프를 해제한다.
     */
    public void startReaderLoop(NetworkPlayer player, List<Player> players,
            Runnable onDisconnect) {
        this.networkPlayer = player;
        this.allPlayers = players;
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    Object msg = ois.readObject();
                    route((NetMessage) msg);
                }
            } catch (IOException | ClassNotFoundException e) {
                // 연결 종료
            } finally {
                if (onDisconnect != null) onDisconnect.run();
            }
        }, "net-reader");
        t.setDaemon(true);
        t.start();
    }

    private void route(NetMessage msg) {
        switch (msg) {
            case NetMessage.SplitDecision m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.SPLIT)) return; // stale·중복·wrong-type
                var bundleA = resolveCards(m.bundleAIds());
                var bundleB = resolveCards(m.bundleBIds());
                Card fd = resolveCard(m.faceDownId(), networkPlayer.currentHand);
                networkPlayer.provideSplit(new SplitDecision(bundleA, bundleB, fd));
            }
            case NetMessage.ChoiceDecision m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.CHOICE)) return;
                networkPlayer.provideChoice(m.index());
            }
            case NetMessage.HelpersDecision m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.HELPERS)) return;
                var options = networkPlayer.currentHelperOptions;
                var selected = m.helperIds().stream()
                        .map(id -> WireCodec.resolveHelper(id, options))
                        .toList();
                networkPlayer.provideHelpers(selected);
            }
            case NetMessage.CashAction m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.CASH)) return;
                routeCashAction(m);
            }
            case NetMessage.CashPass m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.CASH)) return;
                networkPlayer.passCash();
            }
            default -> {} // 핸드셰이크 등 무시
        }
    }

    private void routeCashAction(NetMessage.CashAction m) {
        switch (m.type()) {
            case "CASH" -> {
                var cards = resolveFromHoldings(m.cardIds());
                networkPlayer.submitCash(new CashInAction.Cash(cards));
            }
            case "CASH_WITH_HELPERS" -> {
                // cardIds = 환금 카드, selectedCardIds = 반응 도우미 id 목록
                var cards = resolveFromHoldings(m.cardIds());
                var helperList = networkPlayer.helpers().stream()
                        .filter(h -> m.selectedCardIds().contains(h.id()))
                        .toList();
                networkPlayer.submitCash(new CashInAction.CashWithHelpers(cards, helperList));
            }
            case "DISCARD" -> {
                Card card = resolveCard(m.cardIds().get(0), networkPlayer.holdings());
                networkPlayer.submitCash(new CashInAction.Discard(card));
            }
            case "USE_HELPER" -> {
                HelperCard helper = WireCodec.resolveHelper(m.helperId(), networkPlayer.helpers());
                HelperCard copyTarget = m.copyTargetId() != null
                        ? findUsedHelper(m.copyTargetId()) : null;
                var selected = m.selectedCardIds().stream()
                        .map(id -> resolveCard(id, networkPlayer.holdings()))
                        .toList();
                networkPlayer.submitCash(new CashInAction.UseHelper(helper, copyTarget, selected));
            }
        }
    }

    private List<Card> resolveCards(List<Integer> ids) {
        return ids.stream()
                .map(id -> resolveCard(id, networkPlayer.currentHand))
                .toList();
    }

    private List<Card> resolveFromHoldings(List<Integer> ids) {
        return ids.stream()
                .map(id -> resolveCard(id, networkPlayer.holdings()))
                .toList();
    }

    private Card resolveCard(int id, java.util.Collection<? extends Card> candidates) {
        return WireCodec.resolveCard(id, candidates);
    }

    private HelperCard findUsedHelper(int id) {
        // 사용된 도우미는 모든 플레이어에서 탐색
        for (Player p : allPlayers) {
            for (HelperCard h : p.helpers()) {
                if (h.id() == id) return h;
            }
        }
        throw new IllegalArgumentException("사용된 도우미 id 를 찾을 수 없음: " + id);
    }

    /** 현재 호스트 IP 주소 문자열 반환(LAN 접속용). */
    public String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public int port() {
        return DEFAULT_PORT;
    }

    @Override
    public void close() throws IOException {
        if (clientSocket != null && !clientSocket.isClosed()) {
            clientSocket.close();
        }
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
