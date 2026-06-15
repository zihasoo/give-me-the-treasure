package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 환금 단계 의사결정에 필요한 읽기 전용 상황값.
 *
 * <p>{@code teamCoins} 와 {@code winningCoins} 를 함께 보면 승리까지 남은 코인을 알 수 있어,
 * 승리 임박 시 미래 잠재력보다 즉시 환금을 우선하는 전략을 세울 수 있다.
 */
public record CashInContext(
        List<Card> holdings,
        List<HelperCard> helpers,
        List<HelperCard> usedHelpers,
        List<Card> discardPile,
        int teamCoins,
        int holdLimit,
        int winningCoins,
        List<Card> opponentHoldings) {

    public CashInContext {
        holdings = List.copyOf(holdings);
        helpers = List.copyOf(helpers);
        usedHelpers = List.copyOf(usedHelpers);
        discardPile = List.copyOf(discardPile);
        opponentHoldings = List.copyOf(opponentHoldings);
    }
}
