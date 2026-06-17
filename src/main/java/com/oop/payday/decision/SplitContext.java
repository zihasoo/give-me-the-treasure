package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.officer.OfficerTile;

/**
 * 봇 전략이 분할(꾀부리기)을 결정할 때 보는 읽기 전용 상황값.
 *
 * <p><b>봇 전용 컨텍스트</b>다 — 네트워크로 직렬화되지 않으며(원격 사람의 분할 요청은
 * {@code onRequestSplit(hand)} 별도 경로를 탄다), 사람/네트워크 플레이어는 {@code hand} 외 필드를 무시한다.
 * 코인 상황({@code myCoins}/{@code opponentCoins}/{@code winningCoins})과 보유 카드({@code holdings})를
 * 함께 보면 묶음과 기존 패의 시너지·승리 임박·견제 같은 상황형 분할을 세울 수 있다.
 *
 * @param hand              이번에 나눠야 할 손패 5장
 * @param holdings          분할자(리더)의 현재 보관 카드 — 묶음과의 시너지 평가용
 * @param myCoins           내 팀 코인
 * @param opponentCoins     상대 팀 코인
 * @param winningCoins      승리 목표 코인
 * @param opponentHoldings  상대 팀 전원의 보관 카드(규칙상 앞면 공개) — 봇 견제 평가용.
 *                          도우미(비공개)는 포함하지 않는다.
 * @param discardPile       버림 더미(규칙상 공개) — 카드 카운팅·성장 추정용.
 * @param helpers           분할자 자신의 미사용 도우미 — 종반 실현 코인(ALPHA 즉승·LUCKY) 추정용.
 * @param officer           분할자(리더)의 간부 타일(없으면 {@code null}) — 종반 리더 보너스(JOE 등) 추정용.
 */
public record SplitContext(
        List<Card> hand,
        List<Card> holdings,
        int myCoins,
        int opponentCoins,
        int winningCoins,
        List<Card> opponentHoldings,
        List<Card> discardPile,
        List<HelperCard> helpers,
        OfficerTile officer) {

    public SplitContext {
        hand = List.copyOf(hand);
        holdings = List.copyOf(holdings);
        opponentHoldings = List.copyOf(opponentHoldings);
        discardPile = List.copyOf(discardPile);
        helpers = List.copyOf(helpers);
    }
}
