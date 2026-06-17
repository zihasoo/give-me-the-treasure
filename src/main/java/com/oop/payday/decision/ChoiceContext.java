package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.officer.OfficerTile;

/**
 * 봇 전략이 분배(묶음 선택)를 결정할 때 보는 읽기 전용 상황값.
 *
 * <p><b>봇 전용 컨텍스트</b>다 — 네트워크로 직렬화되지 않으며(원격 사람의 선택 요청은
 * {@code onRequestChoice(view)} 별도 경로를 탄다), 사람/네트워크 플레이어는 {@code view} 외 필드를 무시한다.
 * 보유 카드와의 시너지, 즉시 승리, 종반 견제(안 가져간 묶음은 상대 몫)를 함께 판단하게 한다.
 *
 * @param view              선택 팀에게 제시된 두 묶음
 * @param holdings          선택자(리더)의 현재 보관 카드 — 묶음과의 시너지 평가용
 * @param myCoins           내 팀 코인
 * @param opponentCoins     상대 팀 코인
 * @param winningCoins      승리 목표 코인
 * @param opponentHoldings  상대 팀(분할자) 전원의 보관 카드(규칙상 앞면 공개) — 봇 견제 평가용.
 *                          도우미(비공개)는 포함하지 않는다.
 * @param discardPile       버림 더미(규칙상 공개) — 카드 카운팅·성장 추정용.
 * @param helpers           선택자 자신의 미사용 도우미 — 종반 실현 코인(ALPHA 즉승·LUCKY) 추정용.
 * @param officer           선택자(리더)의 간부 타일(없으면 {@code null}) — 종반 리더 보너스(JOE 등) 추정용.
 */
public record ChoiceContext(
        ChoiceView view,
        List<Card> holdings,
        int myCoins,
        int opponentCoins,
        int winningCoins,
        List<Card> opponentHoldings,
        List<Card> discardPile,
        List<HelperCard> helpers,
        OfficerTile officer) {

    public ChoiceContext {
        holdings = List.copyOf(holdings);
        opponentHoldings = List.copyOf(opponentHoldings);
        discardPile = List.copyOf(discardPile);
        helpers = List.copyOf(helpers);
    }
}
