package com.oop.payday.model.card;

/**
 * 일반 보물 카드: 색(4종) × 숫자(1~7). 각 (색, 숫자) 조합은 덱에 1장씩 존재한다.
 */
public final class TreasureCard extends Card {

    public static final int MIN_NUMBER = 1;
    public static final int MAX_NUMBER = 7;

    private final CardColor color;
    private final int number;

    public TreasureCard(int id, CardColor color, int number) {
        super(id);
        if (number < MIN_NUMBER || number > MAX_NUMBER) {
            throw new IllegalArgumentException("보물 숫자는 1~7 이어야 합니다: " + number);
        }
        this.color = color;
        this.number = number;
    }

    public CardColor color() {
        return color;
    }

    public int number() {
        return number;
    }

    @Override
    public boolean canFormSet() {
        return true;
    }

    @Override
    public String displayName() {
        return color.korean() + " " + number;
    }
}
