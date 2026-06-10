package com.oop.payday.net;

import java.util.List;

import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.game.Game;
import com.oop.payday.game.GameListener;
import com.oop.payday.game.Phase;
import com.oop.payday.game.Team;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.net.GameServer.ClientSession;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.player.NetworkPlayer.RequestKind;
import com.oop.payday.player.Player;

/**
 * 호스트 측 {@link GameListener}: 게임 이벤트를 직렬화해 연결된 모든 클라이언트로 방송한다.
 *
 * <p><b>공개 이벤트</b>는 모든 클라이언트에게 보내되, 각 클라이언트가 보는 보드 스냅샷은
 * 그 클라이언트 관점으로(자기 팀 도우미는 공개, 상대 팀은 숨김) 별도로 만든다.
 *
 * <p><b>비공개 이벤트</b>(꾀부리기 손패·분할/선택/도우미/분배 요청·환금 패널)는 해당 플레이어를
 * 소유한 클라이언트 1명에게만 보낸다. 호스트 본인/봇이 주체이면 전송하지 않는다.
 *
 * <p>게임 스레드에서 호출되므로 송신은 {@link GameServer#sendTo}(세션별 oos synchronized)로 직렬화된다.
 */
public final class NetworkBroadcaster implements GameListener {

    private final GameServer server;
    private final Team teamA;
    private final Team teamB;
    private Game game;
    private final List<Player> allPlayers;

    public NetworkBroadcaster(GameServer server, Team teamA, Team teamB,
            Game game, List<Player> allPlayers) {
        this.server = server;
        this.teamA = teamA;
        this.teamB = teamB;
        this.game = game;
        this.allPlayers = allPlayers;
    }

    /** Game 인스턴스를 나중에 설정한다 (Game 생성 전에 broadcaster 를 만들어야 할 때). */
    public void setGame(Game g) {
        this.game = g;
    }

    // ── 공개 이벤트 (모든 클라이언트, 관점별 스냅샷) ──────────────────

    @Override
    public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        sendPublic(new GameEvent.PhaseChanged(phase, round, teamId(splitTeam)));
    }

    @Override
    public void onGameSetup(List<Player> players) {
        sendPublic(new GameEvent.GameSetup(players.stream().map(this::playerId).toList()));
    }

    @Override
    public void onPlayerSetup(Player player) {
        sendPublic(new GameEvent.PlayerSetup(playerId(player)));
    }

    @Override
    public void onChoiceReady(BundlePair bundles) {
        sendPublic(new GameEvent.ChoiceReady(
                WireCodec.toDtos(bundles.visible0()), bundles.faceDown0(),
                WireCodec.toDtos(bundles.visible1()), bundles.faceDown1()));
    }

    @Override
    public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        sendPublic(new GameEvent.Distributed(
                chosenIndex,
                teamId(chooseTeam), WireCodec.toDtos(chooseCards),
                teamId(splitTeam), WireCodec.toDtos(splitCards)));
    }

    @Override
    public void onCashIn(Player player, TreasureSet set) {
        sendPublic(new GameEvent.CashIn(playerId(player), WireCodec.toDto(set)));
    }

    @Override
    public void onDiscard(Player player, Card card) {
        sendPublic(new GameEvent.Discard(playerId(player), WireCodec.toDto(card)));
    }

    @Override
    public void onHelperUsed(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        sendPublic(new GameEvent.HelperUsed(
                playerId(player),
                WireCodec.toDto(helper, false),
                message,
                WireCodec.toDtos(drawn),
                WireCodec.toDtos(discarded)));
    }

    @Override
    public void onForcedDiscard(Player player, List<Card> cards) {
        sendPublic(new GameEvent.ForcedDiscard(playerId(player), WireCodec.toDtos(cards)));
    }

    @Override
    public void onCoinsChanged(Team team, int delta) {
        sendPublic(new GameEvent.CoinsChanged(teamId(team), delta));
    }

    @Override
    public void onRoundEnd(int round) {
        sendPublic(new GameEvent.RoundEnd(round));
    }

    @Override
    public void onGameOver(Team winner) {
        sendPublic(new GameEvent.GameOver(teamId(winner)));
    }

    @Override
    public void onMessage(String message) {
        sendPublic(new GameEvent.Message(message));
    }

    @Override
    public void onStealActivated(Player player, Card drawnCard) {
        sendPublic(new GameEvent.StealActivated(playerId(player), WireCodec.nullableTo(drawnCard)));
    }

    // ── 비공개 이벤트 (소유 클라이언트에게만) ────────────────────────

    @Override
    public void onHandDealt(Player splitter, List<Card> hand) {
        ClientSession s = sessionFor(splitter);
        if (s == null) return;
        sendTo(s, new GameEvent.HandDealt(playerId(splitter), WireCodec.toDtos(hand)));
    }

    @Override
    public void onCashDone(Player player) {
        ClientSession s = sessionFor(player);
        if (s == null) return;
        sendTo(s, new GameEvent.CashDone(playerId(player)));
    }

    @Override
    public void onRequestSplit(Player player, List<Card> hand) {
        ClientSession s = sessionFor(player);
        if (s == null) return;
        long rid = s.player().nextRequestId(RequestKind.SPLIT);
        sendTo(s, new GameEvent.RequestSplit(rid, WireCodec.toDtos(hand)));
    }

    @Override
    public void onRequestChoice(Player player, ChoiceView view) {
        ClientSession s = sessionFor(player);
        if (s == null) return;
        var b0 = view.bundle(0);
        var b1 = view.bundle(1);
        long rid = s.player().nextRequestId(RequestKind.CHOICE);
        sendTo(s, new GameEvent.RequestChoice(rid,
                WireCodec.toDtos(b0.visibleCards()), b0.hasFaceDown(),
                WireCodec.toDtos(b1.visibleCards()), b1.hasFaceDown()));
    }

    @Override
    public void onRequestHelpers(Player player, List<HelperCard> options, int chooseCount) {
        ClientSession s = sessionFor(player);
        if (s == null) return;
        List<HelperDto> dtos = options.stream().map(h -> WireCodec.toDto(h, false)).toList();
        long rid = s.player().nextRequestId(RequestKind.HELPERS);
        sendTo(s, new GameEvent.RequestHelpers(rid, playerId(player), dtos, chooseCount));
    }

    @Override
    public void onRequestTeamDistribution(Player leader, Team team, List<Card> acquired) {
        ClientSession s = sessionFor(leader);
        if (s == null) return;
        long rid = s.player().nextRequestId(RequestKind.DISTRIBUTION);
        sendTo(s, new GameEvent.RequestTeamDistribution(
                rid, playerId(leader), teamId(team), WireCodec.toDtos(acquired)));
    }

    @Override
    public void onCashTurn(Player player, CashInContext snapshot) {
        ClientSession s = sessionFor(player);
        if (s == null) return;
        CashInContextDto ctx = new CashInContextDto(
                WireCodec.toDtos(snapshot.holdings()),
                snapshot.helpers().stream().map(h -> WireCodec.toDto(h, false)).toList(),
                snapshot.usedHelpers().stream().map(h -> WireCodec.toDto(h, false)).toList(),
                WireCodec.toDtos(snapshot.discardPile()),
                snapshot.teamCoins(),
                snapshot.holdLimit());
        long rid = s.player().nextRequestId(RequestKind.CASH);
        sendTo(s, new GameEvent.CashTurn(playerId(player), rid, ctx));
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────

    /** 공개 이벤트를 연결된 모든 클라이언트에 각자 관점의 스냅샷과 함께 보낸다. */
    private void sendPublic(GameEvent event) {
        for (ClientSession s : server.sessions()) {
            if (s.player() == null) continue;
            server.sendTo(s.clientId(), new NetMessage.Envelope(event, perspectiveState(s.player())));
        }
    }

    /** 한 세션에 이벤트를 그 클라이언트 관점의 스냅샷과 함께 보낸다. */
    private void sendTo(ClientSession s, GameEvent event) {
        server.sendTo(s.clientId(), new NetMessage.Envelope(event, perspectiveState(s.player())));
    }

    /** 이 플레이어를 소유한 클라이언트 세션. 호스트 사람/봇이면 {@code null}. */
    private ClientSession sessionFor(Player player) {
        if (!(player instanceof NetworkPlayer)) return null;
        for (ClientSession s : server.sessions()) {
            if (s.player() == player) return s;
        }
        return null;
    }

    private PublicBoardState perspectiveState(Player clientPlayer) {
        int cpid = playerId(clientPlayer);
        List<TeamStateDto> teams = List.of(
                WireCodec.toTeamDto(teamA, 0, cpid, allPlayers),
                WireCodec.toTeamDto(teamB, 1, cpid, allPlayers));
        List<CardDto> discard = WireCodec.toDtos(game.discardView());
        return new PublicBoardState(teams, discard);
    }

    private int playerId(Player player) {
        int idx = allPlayers.indexOf(player);
        if (idx < 0) throw new IllegalStateException("알 수 없는 플레이어: " + player);
        return idx;
    }

    private int teamId(Team team) {
        if (team == teamA) return 0;
        if (team == teamB) return 1;
        throw new IllegalStateException("알 수 없는 팀: " + team);
    }
}
