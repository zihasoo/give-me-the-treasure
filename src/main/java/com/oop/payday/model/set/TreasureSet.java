package com.oop.payday.model.set;

import java.util.List;

import com.oop.payday.model.card.Card;

/**
 * 환금 가능한 카드 묶음 한 벌: 카드 목록 + 판정된 세트 종류 + 코인값(불변).
 * {@link SetEvaluator} 가 생성한다.
 */
public final class TreasureSet {

    private final List<Card> cards;
    private final SetType type;
    private final int coin;

    public TreasureSet(List<Card> cards, SetType type, int coin) {
        this.cards = List.copyOf(cards);
        this.type = type;
        this.coin = coin;
    }

    public List<Card> cards() {
        return cards;
    }

    public SetType type() {
        return type;
    }

    public int coin() {
        return coin;
    }

    public int size() {
        return cards.size();
    }

    @Override
    public String toString() {
        return String.format("%s %d장 → %d코인 %s", type.korean(), size(), coin, cards);
    }
}
