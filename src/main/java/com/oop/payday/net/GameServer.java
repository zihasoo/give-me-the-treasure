package com.oop.payday.net;

import java.io.Closeable;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.player.NetworkPlayer.RequestKind;
import com.oop.payday.player.Player;

/**
 * 호스트 측 TCP 서버(다중 클라이언트).
 *
 * <ol>
 *   <li><b>대기실 단계</b>: {@link #startAccepting} 로 클라이언트 연결을 계속 수락한다.
 *       접속마다 {@link ClientSession} 을 만들고 clientId 를 발급하며 리더 스레드를 시작한다.
 *       연결/대기실 메시지/해제는 {@link ClientListener} 콜백으로 알린다.
 *   <li><b>게임 단계</b>: {@link #beginGame} 로 진입한 뒤, 각 세션에 {@link #bindPlayer} 로
 *       {@link NetworkPlayer} 를 묶는다. 리더 스레드는 결정 메시지를 세션의 대리자로 라우팅한다.
 * </ol>
 *
 * 모든 콜백은 서버 백그라운드 스레드에서 호출되므로, UI 변경은 구현체가 {@code Platform.runLater}
 * 로 감싸야 한다.
 */
public final class GameServer implements Closeable {

    public static final int DEFAULT_PORT = 23456;

    /** 대기실/게임 진행 중 클라이언트 연결 이벤트 콜백(서버 백그라운드 스레드에서 호출). */
    public interface ClientListener {
        void onClientConnected(int clientId);
        void onLobbyMessage(int clientId, NetMessage msg);
        void onClientDisconnected(int clientId);
    }

    /** 한 클라이언트 연결. 게임 시작 시 {@code player} 가 묶인다. */
    public static final class ClientSession {
        final int clientId;
        private final Socket socket;
        private final ObjectOutputStream oos;
        private final ObjectInputStream ois;
        private volatile NetworkPlayer player;   // 게임 시작 시 바인딩
        private volatile String name;

        ClientSession(int clientId, Socket socket, ObjectOutputStream oos, ObjectInputStream ois) {
            this.clientId = clientId;
            this.socket = socket;
            this.oos = oos;
            this.ois = ois;
        }

        public int clientId() {
            return clientId;
        }

        public NetworkPlayer player() {
            return player;
        }

        public String name() {
            return name;
        }
    }

    private final ServerSocket serverSocket;
    private final int boundPort;
    private final Map<Integer, ClientSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger nextClientId = new AtomicInteger(1);
    private volatile ClientListener listener;
    private volatile boolean accepting;

    /** 게임 시작 시 설정 — 사용된 도우미 id 복원에 쓰는 전체 플레이어 목록. */
    private volatile List<Player> allPlayers = List.of();

    public GameServer() throws IOException {
        this(DEFAULT_PORT);
    }

    public GameServer(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        boundPort = serverSocket.getLocalPort();
    }

    public void setClientListener(ClientListener listener) {
        this.listener = listener;
    }

    // ── 대기실: 연결 수락 ────────────────────────────────────────────

    /** 대기실 단계: 클라이언트 연결을 백그라운드에서 계속 수락한다. */
    public void startAccepting() {
        if (accepting) return;
        accepting = true;
        Thread t = new Thread(this::acceptLoop, "lobby-accept");
        t.setDaemon(true);
        t.start();
    }

    /** 수락을 멈춘다(게임 시작 시). 이미 연결된 세션은 유지한다. */
    public void stopAccepting() {
        accepting = false;
        try {
            if (!serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {
            // 이미 닫힘
        }
    }

    private void acceptLoop() {
        while (accepting) {
            Socket sock;
            try {
                sock = serverSocket.accept();
            } catch (IOException e) {
                break; // serverSocket 닫힘 → 수락 종료
            }
            ClientSession session;
            try {
                ObjectOutputStream oos = new ObjectOutputStream(sock.getOutputStream());
                oos.flush();
                ObjectInputStream ois = new ObjectInputStream(sock.getInputStream());
                int id = nextClientId.getAndIncrement();
                session = new ClientSession(id, sock, oos, ois);
                sessions.put(id, session);
            } catch (IOException e) {
                try { sock.close(); } catch (IOException ignored) {}
                continue;
            }
            startReader(session);
            ClientListener l = listener;
            if (l != null) l.onClientConnected(session.clientId);
        }
    }

    private void startReader(ClientSession session) {
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    NetMessage msg = (NetMessage) session.ois.readObject();
                    NetworkPlayer p = session.player;
                    if (p != null && isDecision(msg)) {
                        route(p, msg);
                    } else if (msg instanceof NetMessage.LobbyHello hello) {
                        session.name = hello.name();
                        ClientListener l = listener;
                        if (l != null) l.onLobbyMessage(session.clientId, msg);
                    } else {
                        ClientListener l = listener;
                        if (l != null) l.onLobbyMessage(session.clientId, msg);
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                // 연결 종료
            } finally {
                sessions.remove(session.clientId);
                try { session.socket.close(); } catch (IOException ignored) {}
                ClientListener l = listener;
                if (l != null) l.onClientDisconnected(session.clientId);
            }
        }, "net-reader-" + session.clientId);
        t.setDaemon(true);
        t.start();
    }

    private static boolean isDecision(NetMessage msg) {
        return msg instanceof NetMessage.SplitDecision
                || msg instanceof NetMessage.ChoiceDecision
                || msg instanceof NetMessage.HelpersDecision
                || msg instanceof NetMessage.DistributionDecision
                || msg instanceof NetMessage.CashAction
                || msg instanceof NetMessage.CashPass;
    }

    // ── 게임 단계: 대리자 바인딩 ─────────────────────────────────────

    /** 게임 시작 진입 — 전체 플레이어 목록을 등록한다(사용된 도우미 id 복원용). */
    public void beginGame(List<Player> allPlayers) {
        this.allPlayers = List.copyOf(allPlayers);
    }

    /** 세션에 네트워크 대리자를 묶는다(게임 시작 시). 이후 그 세션의 결정 메시지가 대리자로 라우팅된다. */
    public void bindPlayer(int clientId, NetworkPlayer player) {
        ClientSession s = sessions.get(clientId);
        if (s != null) s.player = player;
    }

    // ── 송신 ─────────────────────────────────────────────────────────

    public Collection<ClientSession> sessions() {
        return sessions.values();
    }

    public ClientSession session(int clientId) {
        return sessions.get(clientId);
    }

    /** 한 클라이언트에게 메시지를 보낸다(스레드 안전). 연결이 끊겼으면 조용히 무시. */
    public void sendTo(int clientId, NetMessage msg) {
        ClientSession s = sessions.get(clientId);
        if (s == null) return;
        synchronized (s.oos) {
            try {
                s.oos.writeObject(msg);
                s.oos.reset();
                s.oos.flush();
            } catch (IOException e) {
                // 연결 끊김 — 리더 스레드가 감지해 처리
            }
        }
    }

    /** 모든 클라이언트에게 같은 메시지를 보낸다. */
    public void broadcast(NetMessage msg) {
        for (ClientSession s : sessions.values()) {
            sendTo(s.clientId, msg);
        }
    }

    // ── 결정 메시지 라우팅 ───────────────────────────────────────────

    private void route(NetworkPlayer networkPlayer, NetMessage msg) {
        switch (msg) {
            case NetMessage.SplitDecision m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.SPLIT)) return;
                var bundleA = resolveCards(networkPlayer.currentHand, m.bundleAIds());
                var bundleB = resolveCards(networkPlayer.currentHand, m.bundleBIds());
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
            case NetMessage.DistributionDecision m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.DISTRIBUTION)) return;
                var acquired = networkPlayer.currentAcquired;
                List<List<Card>> byMember = m.byMemberIds().stream()
                        .map(ids -> resolveCards(acquired, ids))
                        .toList();
                networkPlayer.provideDistribution(new TeamDistribution(byMember));
            }
            case NetMessage.CashAction m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.CASH)) return;
                routeCashAction(networkPlayer, m);
            }
            case NetMessage.CashPass m -> {
                if (!networkPlayer.consumeRequest(m.requestId(), RequestKind.CASH)) return;
                networkPlayer.passCash();
            }
            default -> {} // 핸드셰이크/대기실 메시지 등 무시
        }
    }

    private void routeCashAction(NetworkPlayer networkPlayer, NetMessage.CashAction m) {
        switch (m.type()) {
            case "CASH" -> {
                var cards = resolveCards(networkPlayer.holdings(), m.cardIds());
                networkPlayer.submitCash(new CashInAction.Cash(cards));
            }
            case "CASH_WITH_HELPERS" -> {
                var cards = resolveCards(networkPlayer.holdings(), m.cardIds());
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

    private List<Card> resolveCards(Collection<? extends Card> candidates, List<Integer> ids) {
        return ids.stream()
                .map(id -> resolveCard(id, candidates))
                .toList();
    }

    private Card resolveCard(int id, Collection<? extends Card> candidates) {
        return WireCodec.resolveCard(id, candidates);
    }

    private HelperCard findUsedHelper(int id) {
        for (Player p : allPlayers) {
            for (HelperCard h : p.helpers()) {
                if (h.id() == id) return h;
            }
        }
        throw new IllegalArgumentException("사용된 도우미 id 를 찾을 수 없음: " + id);
    }

    // ── 주소/종료 ────────────────────────────────────────────────────

    /** 현재 호스트 IP 주소 문자열 반환(LAN 접속용). */
    public String localAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public int port() {
        return boundPort;
    }

    @Override
    public void close() throws IOException {
        accepting = false;
        for (ClientSession s : sessions.values()) {
            try { s.socket.close(); } catch (IOException ignored) {}
        }
        sessions.clear();
        if (!serverSocket.isClosed()) {
            serverSocket.close();
        }
    }
}
