package com.oop.payday.model.card;

/**
 * 굉장한 보물(와일드). 세트를 만들 때 원하는 색·숫자의 보물로 간주할 수 있다.
 * 덱 전체에 1장만 존재한다.
 */
public final class WildCard extends Card {

    public WildCard(int id) {
        super(id);
    }

    @Override
    public boolean isWild() {
        return true;
    }

    @Override
    public String displayName() {
        return "굉장한 보물";
    }
}
