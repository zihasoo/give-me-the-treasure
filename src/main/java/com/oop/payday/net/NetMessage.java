package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

/**
 * 호스트↔클라이언트 간 전송되는 모든 메시지의 sealed 루트 타입.
 *
 * <ul>
 *   <li>호스트→클라이언트: {@link Handshake}, {@link Envelope}
 *   <li>클라이언트→호스트: {@link SplitDecision}, {@link ChoiceDecision},
 *       {@link HelpersDecision}, {@link CashAction}, {@link CashPass}
 * </ul>
 */
public sealed interface NetMessage extends Serializable
        permits NetMessage.Handshake, NetMessage.Restart, NetMessage.Envelope,
                NetMessage.SplitDecision, NetMessage.ChoiceDecision,
                NetMessage.HelpersDecision, NetMessage.CashAction, NetMessage.CashPass {

    /** 게임 시작 전 호스트→클라이언트 핸드셰이크. */
    record Handshake(
            int winningCoins,
            boolean leaderEffectsEnabled,
            int clientTeamId,
            PublicBoardState initialState) implements NetMessage {}

    /**
     * 게임 종료/일시정지 후 호스트가 같은 연결로 새 판을 시작할 때 보내는 재시작 통지.
     * 페이로드는 {@link Handshake} 와 동일하지만, 진행 중 스트림에서 reader 가 구분해
     * 미러를 새로 초기화하도록 별도 타입으로 둔다.
     */
    record Restart(
            int winningCoins,
            boolean leaderEffectsEnabled,
            int clientTeamId,
            PublicBoardState initialState) implements NetMessage {}

    /** 매 게임 이벤트마다 호스트가 보내는 봉투: 이벤트 + 공개 보드 스냅샷. */
    record Envelope(GameEvent event, PublicBoardState state) implements NetMessage {}

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
     * type: "CASH" | "CASH_WITH_HELPERS" | "DISCARD" | "USE_HELPER"
     * cardIds: 환금 카드 목록(또는 처분 카드 1장)
     * helperId / copyTargetId / selectedCardIds: USE_HELPER 에서만 사용.
     */
    record CashAction(
            long requestId,
            String type,
            List<Integer> cardIds,
            Integer helperId,
            Integer copyTargetId,
            List<Integer> selectedCardIds) implements NetMessage {}

    record CashPass(long requestId) implements NetMessage {}
}
