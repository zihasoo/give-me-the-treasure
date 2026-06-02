package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 환금 단계 의사결정에 필요한 읽기 전용 상황값.
 */
public record CashInContext(
        List<Card> holdings,
        List<HelperCard> helpers,
        List<HelperCard> usedHelpers,
        List<Card> discardPile,
        int teamCoins,
        int holdLimit) {

    public CashInContext {
        holdings = List.copyOf(holdings);
        helpers = List.copyOf(helpers);
        usedHelpers = List.copyOf(usedHelpers);
        discardPile = List.copyOf(discardPile);
    }
}
