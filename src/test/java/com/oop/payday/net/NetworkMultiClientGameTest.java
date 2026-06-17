package com.oop.payday.net;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.S7BotStrategy;
import com.oop.payday.game.Game;
import com.oop.payday.game.GameConfig;
import com.oop.payday.game.GameListener;
import com.oop.payday.game.Team;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.player.Player;

/**
 * 다중 클라이언트 네트워크 경로의 헤드리스 통합 검증.
 *
 * <p>실제 {@link GameServer} 를 띄우고 루프백으로 두 클라이언트를 접속시킨다. 각 팀의 리더는 봇,
 * 멤버는 원격 {@link NetworkPlayer} 로 구성해 2v2 게임을 끝까지 돌린다. 테스트 클라이언트는
 * 받은 환금 패널({@code CashTurn})에 즉시 "턴 종료"로 응답하고 나머지 이벤트는 흘려보낸다.
 *
 * <p>이로써 ① 다중 소켓 수락·세션 라우팅, ② 클라이언트별 관점 보드 방송, ③ {@link NetworkPlayer}
 * 환금 제출 경로, ④ 2v2 게임 완주가 한 번에 회귀 보호된다.
 */
final class NetworkMultiClientGameTest {

    @Tag("integration")
    @Test
    void twoRemoteMembersGameFinishes() throws Exception {
        GameServer server = new GameServer(0); // 임의의 빈 포트
        LinkedBlockingQueue<Integer> connected = new LinkedBlockingQueue<>();
        server.setClientListener(new GameServer.ClientListener() {
            @Override public void onClientConnected(int clientId) { connected.add(clientId); }
            @Override public void onLobbyMessage(int clientId, NetMessage msg) { }
            @Override public void onClientDisconnected(int clientId) { }
        });
        server.startAccepting();

        List<TestClient> clients = new ArrayList<>();
        try {
            // 두 클라이언트를 순차로 접속해 clientId 를 확정한다.
            TestClient cA = new TestClient(server.port());
            int idA = await(connected);
            TestClient cB = new TestClient(server.port());
            int idB = await(connected);
            clients.add(cA);
            clients.add(cB);
            cA.start();
            cB.start();

            // 팀 구성: 리더=봇, 멤버=원격. allPlayers 순서 = teamA(리더,멤버) + teamB(리더,멤버).
            NetworkPlayer npA = new NetworkPlayer("원격 A");
            NetworkPlayer npB = new NetworkPlayer("원격 B");
            Team teamA = new Team("팀 A", List.of(BotPlayer.test(new S7BotStrategy()), npA));
            Team teamB = new Team("팀 B", List.of(BotPlayer.test(new S7BotStrategy()), npB));
            List<Player> allPlayers = new ArrayList<>(teamA.members());
            allPlayers.addAll(teamB.members());

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Team> winnerRef = new AtomicReference<>();
            GameListener capture = new GameListener() {
                @Override public void onGameOver(Team winner) {
                    winnerRef.set(winner);
                    done.countDown();
                }
            };

            NetworkBroadcaster broadcaster = new NetworkBroadcaster(server, teamA, teamB, null, allPlayers, 1);
            FanOutGameListener fanOut = new FanOutGameListener(capture, broadcaster);
            GameConfig config = GameConfig.practice(true);
            Game game = new Game(config, teamA, teamB, fanOut);
            broadcaster.setGame(game);

            server.beginGame(allPlayers);
            // 핸드셰이크(자기 관점 초기 상태 포함) 전송 후 세션에 대리자 바인딩.
            sendHandshakeAndBind(server, idA, npA, 0, teamA, teamB, game, allPlayers, config);
            sendHandshakeAndBind(server, idB, npB, 1, teamA, teamB, game, allPlayers, config);

            Thread loop = new Thread(game::play, "test-game-loop");
            loop.setDaemon(true);
            loop.start();

            boolean finished = done.await(60, TimeUnit.SECONDS);
            if (!finished) {
                game.abort();
            }
            assertTrue(finished, "2v2 원격 게임은 제한 시간 내에 끝나야 한다.");
            Team winner = winnerRef.get();
            assertNotNull(winner, "승자가 결정되어야 한다.");
            assertTrue(winner == teamA || winner == teamB, "승자는 참가 팀 중 하나여야 한다.");
        } finally {
            for (TestClient c : clients) c.close();
            server.close();
        }
    }

    private static void sendHandshakeAndBind(GameServer server, int clientId, NetworkPlayer np,
            int teamId, Team teamA, Team teamB, Game game, List<Player> allPlayers, GameConfig config) {
        int playerId = allPlayers.indexOf(np);
        PublicBoardState init = WireCodec.buildState(teamA, teamB, game, playerId, allPlayers);
        server.sendTo(clientId, new NetMessage.Handshake(
                1, config.winningCoins(), config.leaderEffectsEnabled(), teamId, playerId, init));
        server.bindPlayer(clientId, np);
    }

    private static int await(LinkedBlockingQueue<Integer> q) throws InterruptedException {
        Integer id = q.poll(5, TimeUnit.SECONDS);
        assertNotNull(id, "클라이언트 접속이 서버에 등록되어야 한다.");
        return id;
    }

    /** 루프백 테스트 클라이언트: 환금 패널엔 즉시 턴 종료로 응답, 나머지는 흘려보낸다. */
    private static final class TestClient {
        private final Socket socket;
        private final ObjectOutputStream oos;
        private final ObjectInputStream ois;
        private volatile boolean running = true;

        TestClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            oos = new ObjectOutputStream(socket.getOutputStream());
            oos.flush();
            ois = new ObjectInputStream(socket.getInputStream());
            // 실제 GameClient 와 같은 역직렬화 필터 — 게임 한 판의 모든 호스트→클라이언트
            // 페이로드가 허용목록을 통과하는지 함께 회귀 보호한다.
            ois.setObjectInputFilter(WireCodec.WIRE_FILTER);
        }

        void start() {
            Thread t = new Thread(this::readLoop, "test-client-reader");
            t.setDaemon(true);
            t.start();
        }

        private void readLoop() {
            try {
                while (running) {
                    NetMessage msg = (NetMessage) ois.readObject();
                    if (msg instanceof NetMessage.Envelope env
                            && env.event() instanceof GameEvent.CashTurn ct) {
                        send(new NetMessage.CashPass(ct.requestId()));
                    }
                }
            } catch (IOException | ClassNotFoundException e) {
                // 연결 종료
            }
        }

        private void send(NetMessage msg) {
            synchronized (oos) {
                try {
                    oos.writeObject(msg);
                    oos.reset();
                    oos.flush();
                } catch (IOException e) {
                    // 연결 종료
                }
            }
        }

        void close() {
            running = false;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
