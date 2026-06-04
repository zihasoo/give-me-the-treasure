package com.oop.payday.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.oop.payday.game.Team;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.player.MirrorPlayer;
import com.oop.payday.player.Player;

/**
 * 클라이언트 측 게임 상태 레지스트리.
 * 수신한 {@link PublicBoardState} 로 미러 {@link Team}/{@link Player}/카드/도우미를 갱신하며,
 * 컨트롤러는 이 객체에서 참조를 꺼내 기존 렌더링 코드를 그대로 사용한다.
 */
public final class ClientMirror {

    private final Map<Integer, Card> cardRegistry = new HashMap<>();
    private final Map<Integer, MirrorHelperCard> helperRegistry = new HashMap<>();
    private final Map<Integer, MirrorPlayer> playerRegistry = new HashMap<>();
    private final Map<Integer, Team> teamRegistry = new HashMap<>();

    private int myTeamId;
    private List<Card> discardPile = List.of();
    private List<Player> allPlayers = List.of();

    /**
     * 핸드셰이크 수신 시 미러를 초기화한다.
     * @param clientTeamId 이 클라이언트가 속한 팀 id (0 또는 1)
     * @param state        초기 보드 스냅샷
     */
    public void init(int clientTeamId, PublicBoardState state) {
        this.myTeamId = clientTeamId;
        // 팀·플레이어 객체 미리 생성
        for (TeamStateDto tDto : state.teams()) {
            for (PlayerStateDto pDto : tDto.members()) {
                playerRegistry.put(pDto.playerId(), new MirrorPlayer(pDto.name()));
            }
            List<MirrorPlayer> members = tDto.members().stream()
                    .map(p -> playerRegistry.get(p.playerId()))
                    .toList();
            teamRegistry.put(tDto.teamId(), new Team(tDto.name(), new ArrayList<>(members)));
        }
        applyState(state);
    }

    /**
     * 새 {@link PublicBoardState} 를 받아 미러 객체들의 상태를 일괄 갱신한다.
     * JavaFX 스레드에서 호출하거나, {@code Platform.runLater} 전에 호출해야 한다.
     */
    public void applyState(PublicBoardState state) {
        discardPile = state.discardPile().stream()
                .map(this::getOrCreateCard)
                .toList();

        List<Player> ordered = new ArrayList<>();
        for (TeamStateDto tDto : state.teams()) {
            Team team = getOrCreateTeam(tDto);
            team.setCoins(tDto.coins());
            for (PlayerStateDto pDto : tDto.members()) {
                MirrorPlayer player = getOrCreatePlayer(pDto);
                applyPlayerState(player, pDto);
                ordered.add(player);
            }
        }
        this.allPlayers = List.copyOf(ordered);
    }

    private void applyPlayerState(MirrorPlayer player, PlayerStateDto dto) {
        player.setOfficer(dto.officer());
        player.setLeader(dto.leader());
        player.setHoldLimit(dto.holdLimit());

        // holdings 갱신
        player.removeAll(new ArrayList<>(player.holdings()));
        for (CardDto c : dto.holdings()) {
            player.receive(getOrCreateCard(c));
        }

        // helpers: 최초 수신 시 추가, 이후엔 used/kind 만 갱신
        if (player.helpers().isEmpty() && !dto.helpers().isEmpty()) {
            List<HelperCard> cards = dto.helpers().stream()
                    .<HelperCard>map(h -> getOrCreateHelper(h))
                    .toList();
            player.receiveHelpers(new ArrayList<>(cards));
        } else {
            for (HelperDto hDto : dto.helpers()) {
                MirrorHelperCard mh = helperRegistry.get(hDto.id());
                if (mh == null) continue;
                if (hDto.kind() != null && mh.kind() == null) {
                    mh.revealKind(hDto.kind());
                }
                if (hDto.used()) {
                    mh.markUsed();
                }
            }
        }
    }

    // ── 레지스트리 접근 ───────────────────────────────────────────────

    public Card getOrCreateCard(CardDto dto) {
        return cardRegistry.computeIfAbsent(dto.id(), id -> WireCodec.cardFromDto(dto));
    }

    public MirrorHelperCard getOrCreateHelper(HelperDto dto) {
        MirrorHelperCard h = helperRegistry.computeIfAbsent(
                dto.id(), id -> new MirrorHelperCard(id, dto.kind()));
        if (dto.kind() != null && h.kind() == null) h.revealKind(dto.kind());
        if (dto.used()) h.markUsed();
        return h;
    }

    public MirrorPlayer getOrCreatePlayer(PlayerStateDto dto) {
        return playerRegistry.computeIfAbsent(dto.playerId(), id -> new MirrorPlayer(dto.name()));
    }

    public Team getOrCreateTeam(TeamStateDto dto) {
        return teamRegistry.computeIfAbsent(dto.teamId(), id -> {
            List<MirrorPlayer> members = dto.members().stream()
                    .map(this::getOrCreatePlayer)
                    .toList();
            return new Team(dto.name(), new ArrayList<>(members));
        });
    }

    // ── 뷰 접근자 ────────────────────────────────────────────────────

    public Team myTeam() {
        return teamRegistry.get(myTeamId);
    }

    public Team opponentTeam() {
        return teamRegistry.get(myTeamId == 0 ? 1 : 0);
    }

    public Player myPlayer() {
        return myTeam().leader();
    }

    public Player playerById(int id) {
        return playerRegistry.get(id);
    }

    public Team teamById(int id) {
        return teamRegistry.get(id);
    }

    public List<Card> discardPile() {
        return discardPile;
    }

    public List<Player> allPlayers() {
        return allPlayers;
    }
}
