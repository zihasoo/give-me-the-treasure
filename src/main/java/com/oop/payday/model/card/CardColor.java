package com.oop.payday.model.card;

/**
 * 보물 카드의 4색. 일반 보물은 (색, 숫자 1~7) 조합이 각각 1장씩 존재한다.
 */
public enum CardColor {
    YELLOW("노랑"),
    RED("빨강"),
    TEAL("청록"),
    BLUE("파랑");

    private final String korean;

    CardColor(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
