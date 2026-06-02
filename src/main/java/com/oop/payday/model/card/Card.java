package com.oop.payday.model.card;

/**
 * 모든 보물/특수 카드의 공통 추상 타입. (상속·다형성의 축)
 *
 * <p>각 카드는 덱 안에서 고유한 {@code id} 를 가진다(같은 종류의 카드라도 구분 가능).
 * 세트 구성 가능 여부 등 종류별로 달라지는 동작은 하위 클래스가 정의한다.
 */
public abstract class Card {

    private final int id;

    protected Card(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** 화면/로그에 표시할 이름 (예: "노랑 5", "굉장한 보물"). */
    public abstract String displayName();

    /**
     * 이 카드가 일반 세트(같은 숫자/연속) 구성에 직접 쓰일 수 있는 보물인가.
     * 굉장한 보물(와일드)은 {@link #isWild()} 로 별도 취급한다.
     */
    public boolean canFormSet() {
        return false;
    }

    /** 굉장한 보물(임의 색·숫자 대체)인가. */
    public boolean isWild() {
        return false;
    }

    @Override
    public String toString() {
        return displayName();
    }
}
