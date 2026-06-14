package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;

/**
 * 봇 전략이 분할(꾀부리기)을 결정할 때 보는 읽기 전용 상황값.
 *
 * <p><b>봇 전용 컨텍스트</b>다 — 네트워크로 직렬화되지 않으며(원격 사람의 분할 요청은
 * {@code onRequestSplit(hand)} 별도 경로를 탄다), 사람/네트워크 플레이어는 {@code hand} 외 필드를 무시한다.
 * 코인 상황({@code myCoins}/{@code opponentCoins}/{@code winningCoins})과 보유 카드({@code holdings})를
 * 함께 보면 묶음과 기존 패의 시너지·승리 임박·견제 같은 상황형 분할을 세울 수 있다.
 *
 * @param hand          이번에 나눠야 할 손패 5장
 * @param holdings      분할자(리더)의 현재 보관 카드 — 묶음과의 시너지 평가용
 * @param myCoins       내 팀 코인
 * @param opponentCoins 상대 팀 코인
 * @param winningCoins  승리 목표 코인
 */
public record SplitContext(
        List<Card> hand,
        List<Card> holdings,
        int myCoins,
        int opponentCoins,
        int winningCoins) {

    public SplitContext {
        hand = List.copyOf(hand);
        holdings = List.copyOf(holdings);
    }
}
