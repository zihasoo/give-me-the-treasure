package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

/**
 * 호스트↔클라이언트 간 전송되는 모든 메시지의 sealed 루트 타입.
 *
 * <ul>
 *   <li>호스트→클라이언트(대기실): {@link LobbyState}, {@link LobbyClosed}
 *   <li>호스트→클라이언트(게임): {@link Handshake}, {@link Restart}, {@link Envelope}
 *   <li>클라이언트→호스트: {@link LobbyHello}, {@link SplitDecision}, {@link ChoiceDecision},
 *       {@link HelpersDecision}, {@link DistributionDecision}, {@link CashAction}, {@link CashPass}
 * </ul>
 *
 * <p>{@code epoch} 는 한 판(게임 세대)의 식별자다. 호스트가 판마다 증가시켜 핸드셰이크/재시작에
 * 실어 보내고, 모든 {@link Envelope} 에도 찍는다. 클라이언트는 현재 세대와 다른 봉투를 버려서
 * 재시작 직후 도착하는 이전 판의 늦은 이벤트가 새 보드를 오염시키지 않게 한다.
 */
public sealed interface NetMessage extends Serializable
        permits NetMessage.Handshake, NetMessage.Restart, NetMessage.Envelope,
                NetMessage.LobbyState, NetMessage.LobbyHello, NetMessage.LobbyClosed,
                NetMessage.SplitDecision, NetMessage.ChoiceDecision,
                NetMessage.HelpersDecision, NetMessage.CashAction, NetMessage.CashPass,
                NetMessage.DistributionDecision,
                NetMessage.DistributionPreview, NetMessage.HelperPreview,
                NetMessage.DistributionDone {

    /**
     * 게임 시작 전 호스트→클라이언트 핸드셰이크.
     * {@code clientPlayerId} 는 이 클라이언트가 조작하는 플레이어의 전역 id 다(다인 팀에서
     * 리더가 아닐 수 있으므로 팀 id 만으로는 부족하다).
     */
    record Handshake(
            int epoch,
            int winningCoins,
            boolean leaderEffectsEnabled,
            int clientTeamId,
            int clientPlayerId,
            PublicBoardState initialState) implements NetMessage {}

    /**
     * 게임 종료/일시정지 후 호스트가 같은 연결로 새 판을 시작할 때 보내는 재시작 통지.
     * 페이로드는 {@link Handshake} 와 동일하지만, 진행 중 스트림에서 reader 가 구분해
     * 미러를 새로 초기화하도록 별도 타입으로 둔다.
     */
    record Restart(
            int epoch,
            int winningCoins,
            boolean leaderEffectsEnabled,
            int clientTeamId,
            int clientPlayerId,
            PublicBoardState initialState) implements NetMessage {}

    /**
     * 대기실 상태 방송(호스트→클라이언트). 슬롯 구성·연습모드·자기 자리(yourClientId)를 담는다.
     * 클라이언트는 이를 받아 대기실 화면을 갱신한다.
     */
    record LobbyState(
            List<LobbySlotView> slots,
            boolean practice,
            int yourClientId) implements NetMessage {}

    /** 접속 직후 클라이언트→호스트 인사: 표시 이름 등록(없으면 호스트가 자동 명명). */
    record LobbyHello(String name) implements NetMessage {}

    /** 호스트가 대기실을 닫음(취소). 클라이언트는 메뉴로 복귀한다. */
    record LobbyClosed(String reason) implements NetMessage {}

    /** 매 게임 이벤트마다 호스트가 보내는 봉투: 게임 세대 + 이벤트 + 공개 보드 스냅샷. */
    record Envelope(int epoch, GameEvent event, PublicBoardState state) implements NetMessage {}

    // --- 클라이언트→호스트 결정 메시지 ---
    // 각 응답은 자신이 답하는 요청의 식별자(requestId)를 echo 한다. 호스트는 현재 대기 중인
    // 요청과 일치하는 응답만 처리하고 stale·중복 응답은 버린다(NetworkPlayer.consumeRequest).

    record SplitDecision(
            long requestId,
            List<Integer> bundleAIds,
            List<Integer> bundleBIds,
            int faceDownId) implements NetMessage {}

    record ChoiceDecision(long requestId, int index) implements NetMessage {}

    record HelpersDecision(long requestId, List<Integer> helperIds) implements NetMessage {}

    /**
     * 환금 행동.
     * <ul>
     *   <li>{@code cardIds}: 환금 카드 목록(CASH/CASH_WITH_HELPERS) 또는 처분 카드 1장(DISCARD)
     *   <li>{@code helperIds}: 환금에 함께 내는 도우미들(CASH_WITH_HELPERS 전용)
     *   <li>{@code helperId}/{@code copyTargetId}/{@code selectedCardIds}: USE_HELPER 전용 —
     *       사용할 도우미·복사 대상·효과 대상 카드
     * </ul>
     */
    record CashAction(
            long requestId,
            Kind kind,
            List<Integer> cardIds,
            List<Integer> helperIds,
            Integer helperId,
            Integer copyTargetId,
            List<Integer> selectedCardIds) implements NetMessage {

        public enum Kind { CASH, CASH_WITH_HELPERS, DISCARD, USE_HELPER }
    }

    record CashPass(long requestId) implements NetMessage {}

    /**
     * 팀 내 분배 결정(다인 팀 리더 클라이언트→호스트).
     * {@code byMemberIds.get(i)} = 팀 멤버 i 에게 줄 카드 id 목록(가져온 카드 기준).
     */
    record DistributionDecision(long requestId, List<List<Integer>> byMemberIds) implements NetMessage {}

    // --- 클라이언트→호스트 진행 상태 동기화(리더 클라이언트, 응답 불필요) ---
    // 같은 팀 팀원에게 리더의 선택 진행을 보여주기 위한 광고성 메시지. 호스트가 같은 팀의
    // 다른 클라이언트로 재방송하고, 호스트 자신이 팀원이면 자기 화면도 갱신한다.

    /** {@code assignment.get(i)} = 가져온 카드 i 를 배정한 팀 멤버 인덱스. */
    record DistributionPreview(List<Integer> assignment) implements NetMessage {}

    /** {@code roles.get(i)} = 도우미 후보 i 의 역할(0=미선택, 1=리더, 2=팀원). */
    record HelperPreview(List<Integer> roles) implements NetMessage {}

    /** 리더 클라이언트가 팀 분배를 확정함 — 호스트가 같은 팀 팀원에게 대기 전환을 알린다. */
    record DistributionDone() implements NetMessage {}

    /**
     * 대기실 슬롯 하나의 직렬화 가능한 표현(렌더링 전용).
     * {@code kind} ∈ {"HUMAN","BOT","REMOTE"}, {@code detail} 은 봇 전략 표시명 등.
     * {@code clientId} 는 원격 점유 슬롯의 클라이언트 id(그 외 -1).
     */
    record LobbySlotView(int teamId, int seatIndex, String kind, String name,
                         String detail, int clientId) implements Serializable {}
}
