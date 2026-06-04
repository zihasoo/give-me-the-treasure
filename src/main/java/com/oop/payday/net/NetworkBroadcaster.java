package com.oop.payday.net;

import java.io.IOException;
import java.io.ObjectOutputStream;
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
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.player.Player;

/**
 * 호스트 측 {@link GameListener}: 게임 이벤트를 직렬화해 클라이언트로 방송한다.
 *
 * <p>비공개 정보 게이팅:
 * <ul>
 *   <li>꾀부리기 손패·선택/도우미 요청·환금 패널 이벤트는 클라이언트 플레이어(=networkPlayer)에게만 전달.
 *   <li>상대(호스트) 플레이어의 미사용 도우미 종류는 숨긴다.
 * </ul>
 *
 * <p>게임 스레드에서 호출되므로 {@link ObjectOutputStream} 쓰기는 synchronized.
 */
public final class NetworkBroadcaster implements GameListener {

    private final NetworkPlayer networkPlayer;
    private final Team hostTeam;
    private final Team clientTeam;
    private Game game;
    private final ObjectOutputStream oos;
    private final List<Player> allPlayers;

    public NetworkBroadcaster(NetworkPlayer networkPlayer, Team hostTeam, Team clientTeam,
            Game game, List<Player> allPlayers, ObjectOutputStream oos) {
        this.networkPlayer = networkPlayer;
        this.hostTeam = hostTeam;
        this.clientTeam = clientTeam;
        this.game = game;
        this.allPlayers = allPlayers;
        this.oos = oos;
    }

    /** Game 인스턴스를 나중에 설정한다 (Game 생성 전에 broadcaster 를 만들어야 할 때). */
    public void setGame(Game g) {
        this.game = g;
    }

    // ── 공개 이벤트 (항상 전송) ──────────────────────────────────────

    @Override
    public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        send(new GameEvent.PhaseChanged(phase, round, teamId(splitTeam)));
    }

    @Override
    public void onGameSetup(java.util.List<Player> players) {
        send(new GameEvent.GameSetup(players.stream().map(this::playerId).toList()));
    }

    @Override
    public void onPlayerSetup(Player player) {
        send(new GameEvent.PlayerSetup(playerId(player)));
    }

    @Override
    public void onChoiceReady(BundlePair bundles) {
        send(new GameEvent.ChoiceReady(
                WireCodec.toDtos(bundles.visible0()), bundles.faceDown0(),
                WireCodec.toDtos(bundles.visible1()), bundles.faceDown1()));
    }

    @Override
    public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        send(new GameEvent.Distributed(
                chosenIndex,
                teamId(chooseTeam), WireCodec.toDtos(chooseCards),
                teamId(splitTeam), WireCodec.toDtos(splitCards)));
    }

    @Override
    public void onCashIn(Player player, TreasureSet set) {
        send(new GameEvent.CashIn(playerId(player), WireCodec.toDto(set)));
    }

    @Override
    public void onCashDone(Player player) {
        if (isClientPlayer(player)) {
            send(new GameEvent.CashDone(playerId(player)));
        }
    }

    @Override
    public void onDiscard(Player player, Card card) {
        send(new GameEvent.Discard(playerId(player), WireCodec.toDto(card)));
    }

    @Override
    public void onHelperUsed(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        send(new GameEvent.HelperUsed(
                playerId(player),
                WireCodec.toDto(helper, false),
                message,
                WireCodec.toDtos(drawn),
                WireCodec.toDtos(discarded)));
    }

    @Override
    public void onForcedDiscard(Player player, List<Card> cards) {
        send(new GameEvent.ForcedDiscard(playerId(player), WireCodec.toDtos(cards)));
    }

    @Override
    public void onCoinsChanged(Team team, int delta) {
        send(new GameEvent.CoinsChanged(teamId(team), delta));
    }

    @Override
    public void onRoundEnd(int round) {
        send(new GameEvent.RoundEnd(round));
    }

    @Override
    public void onGameOver(Team winner) {
        send(new GameEvent.GameOver(teamId(winner)));
    }

    @Override
    public void onMessage(String message) {
        send(new GameEvent.Message(message));
    }

    @Override
    public void onStealActivated(Player player, Card drawnCard) {
        send(new GameEvent.StealActivated(playerId(player), WireCodec.nullableTo(drawnCard)));
    }

    // ── 비공개 이벤트 (클라이언트 플레이어에게만) ──────────────────────

    @Override
    public void onHandDealt(Player splitter, List<Card> hand) {
        if (!isClientPlayer(splitter)) return;
        send(new GameEvent.HandDealt(playerId(splitter), WireCodec.toDtos(hand)));
    }

    @Override
    public void onRequestSplit(Player player, List<Card> hand) {
        if (!isClientPlayer(player)) return;
        send(new GameEvent.RequestSplit(WireCodec.toDtos(hand)));
    }

    @Override
    public void onRequestChoice(Player player, ChoiceView view) {
        if (!isClientPlayer(player)) return;
        var b0 = view.bundle(0);
        var b1 = view.bundle(1);
        send(new GameEvent.RequestChoice(
                WireCodec.toDtos(b0.visibleCards()), b0.hasFaceDown(),
                WireCodec.toDtos(b1.visibleCards()), b1.hasFaceDown()));
    }

    @Override
    public void onRequestHelpers(Player player, List<HelperCard> options, int chooseCount) {
        if (!isClientPlayer(player)) return;
        List<HelperDto> dtos = options.stream().map(h -> WireCodec.toDto(h, false)).toList();
        send(new GameEvent.RequestHelpers(playerId(player), dtos, chooseCount));
    }

    @Override
    public void onCashTurn(Player player, CashInContext snapshot) {
        if (!isClientPlayer(player)) return;
        CashInContextDto ctx = new CashInContextDto(
                WireCodec.toDtos(snapshot.holdings()),
                snapshot.helpers().stream().map(h -> WireCodec.toDto(h, false)).toList(),
                snapshot.usedHelpers().stream().map(h -> WireCodec.toDto(h, false)).toList(),
                WireCodec.toDtos(snapshot.discardPile()),
                snapshot.teamCoins(),
                snapshot.holdLimit());
        send(new GameEvent.CashTurn(playerId(player), ctx));
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────

    private boolean isClientPlayer(Player player) {
        return player == networkPlayer;
    }

    private int playerId(Player player) {
        int idx = allPlayers.indexOf(player);
        if (idx < 0) throw new IllegalStateException("알 수 없는 플레이어: " + player);
        return idx;
    }

    private int teamId(Team team) {
        if (team == hostTeam) return 0;
        if (team == clientTeam) return 1;
        throw new IllegalStateException("알 수 없는 팀: " + team);
    }

    private synchronized void send(GameEvent event) {
        PublicBoardState state = buildStateNow();
        try {
            oos.writeObject(new NetMessage.Envelope(event, state));
            oos.reset();
            oos.flush();
        } catch (IOException e) {
            // 연결이 끊기면 조용히 무시 — GameServer 리더 스레드가 감지해 처리
        }
    }

    private PublicBoardState buildStateNow() {
        int clientPlayerId = playerId(networkPlayer);
        List<TeamStateDto> teams = List.of(
                WireCodec.toTeamDto(hostTeam, 0, clientPlayerId, allPlayers),
                WireCodec.toTeamDto(clientTeam, 1, clientPlayerId, allPlayers));
        List<CardDto> discard = WireCodec.toDtos(game.discardView());
        return new PublicBoardState(teams, discard);
    }
}
