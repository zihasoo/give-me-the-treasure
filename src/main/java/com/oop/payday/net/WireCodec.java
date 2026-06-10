package com.oop.payday.net;

import java.io.ObjectInputFilter;
import java.util.Collection;
import java.util.List;

import com.oop.payday.game.Game;
import com.oop.payday.game.Team;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.StealCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.card.WildCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * 도메인 객체 ↔ 직렬화 가능 DTO 변환 + 호스트 측 id→객체 복원 유틸.
 */
public final class WireCodec {

    /**
     * 네트워크 역직렬화 허용목록. 게임 메시지에 등장하는 타입(우리 패키지 + JDK 기본 컬렉션/래퍼)만
     * 허용해 임의 클래스 역직렬화(가젯 체인) 공격 면을 막는다. 서버·클라이언트 양쪽 스트림에 설정한다.
     */
    public static final ObjectInputFilter WIRE_FILTER = ObjectInputFilter.Config.createFilter(
            "com.oop.payday.**;java.lang.**;java.util.**;!*");

    private WireCodec() {}

    // ── 도메인 → DTO ──────────────────────────────────────────────

    public static CardDto toDto(Card card) {
        if (card instanceof TreasureCard t) {
            return new CardDto(t.id(), CardDto.TREASURE, t.color(), t.number());
        } else if (card instanceof WildCard) {
            return new CardDto(card.id(), CardDto.WILD, null, 0);
        } else if (card instanceof StealCard) {
            return new CardDto(card.id(), CardDto.STEAL, null, 0);
        } else if (card instanceof CursedCard c) {
            return new CardDto(c.id(), CardDto.CURSED, null, c.number());
        }
        throw new IllegalArgumentException("알 수 없는 카드 타입: " + card);
    }

    public static List<CardDto> toDtos(Collection<? extends Card> cards) {
        return cards.stream().map(WireCodec::toDto).toList();
    }

    /**
     * @param hideKind 미사용 도우미의 종류를 숨길지(상대 플레이어 도우미 전송 시 true).
     */
    public static HelperDto toDto(HelperCard helper, boolean hideKind) {
        var kind = (hideKind && !helper.isUsed()) ? null : helper.kind();
        return new HelperDto(helper.id(), kind, helper.isUsed());
    }

    public static TreasureSetDto toDto(TreasureSet set) {
        return new TreasureSetDto(toDtos(set.cards()), set.type(), set.coin());
    }

    public static PlayerStateDto toPlayerDto(Player player, int playerId, boolean hideHelpers) {
        List<HelperDto> helperDtos = player.helpers().stream()
                .map(h -> toDto(h, hideHelpers))
                .toList();
        return new PlayerStateDto(
                playerId,
                player.name(),
                player.officer(),
                player.isLeader(),
                player.holdLimit(),
                player.isHoldLimitSuspended(),
                player.holdingCount(),
                toDtos(player.holdings()),
                helperDtos);
    }

    /**
     * 팀 상태 DTO 변환.
     * @param clientPlayerId 수신 클라이언트가 조작하는 플레이어 id. 그 플레이어가 속한 팀의 도우미는
     *                       (팀원 포함) 그대로 보내고, 상대 팀 members 의 미사용 도우미 종류는 숨긴다
     *                       (같은 팀은 정보를 공유한다 — 규칙서 §6 자유 논의).
     */
    public static TeamStateDto toTeamDto(Team team, int teamId, int clientPlayerId, List<Player> allPlayers) {
        boolean clientOnThisTeam = team.members().stream()
                .anyMatch(p -> allPlayers.indexOf(p) == clientPlayerId);
        List<PlayerStateDto> playerDtos = team.members().stream().map(p -> {
            int pid = allPlayers.indexOf(p);
            return toPlayerDto(p, pid, !clientOnThisTeam);
        }).toList();
        return new TeamStateDto(teamId, team.name(), team.coins(), playerDtos);
    }

    public static PublicBoardState buildState(Team teamA, Team teamB, Game game,
            int clientPlayerId, List<Player> allPlayers) {
        List<TeamStateDto> teams = List.of(
                toTeamDto(teamA, 0, clientPlayerId, allPlayers),
                toTeamDto(teamB, 1, clientPlayerId, allPlayers));
        List<CardDto> discard = game != null ? toDtos(game.discardView()) : List.of();
        return new PublicBoardState(teams, discard);
    }

    // ── id → 객체 복원 (호스트 측) ────────────────────────────────

    public static Card resolveCard(int id, Collection<? extends Card> candidates) {
        return candidates.stream()
                .filter(c -> c.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 카드 id: " + id));
    }

    public static HelperCard resolveHelper(int id, Collection<? extends HelperCard> candidates) {
        return candidates.stream()
                .filter(h -> h.id() == id)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 도우미 id: " + id));
    }

    // ── DTO → 클라이언트 Card 재구성 ─────────────────────────────

    public static Card cardFromDto(CardDto dto) {
        return switch (dto.type()) {
            case CardDto.TREASURE -> new TreasureCard(dto.id(), dto.color(), dto.number());
            case CardDto.WILD     -> new WildCard(dto.id());
            case CardDto.STEAL    -> new StealCard(dto.id());
            case CardDto.CURSED   -> new CursedCard(dto.id(), dto.number());
            default -> throw new IllegalArgumentException("알 수 없는 카드 타입: " + dto.type());
        };
    }

    // ── 현재 보드 상태 스냅샷 (discardView 가 필요없는 버전) ──────

    public static CardDto nullableTo(Card card) {
        return card == null ? null : toDto(card);
    }
}
