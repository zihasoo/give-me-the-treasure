package com.oop.payday.game;

/**
 * 한 라운드를 구성하는 4단계(규칙서 §5). 순서대로 진행되고, 종료 후 역할을 교대한다.
 */
public enum Phase {
    /** 꾀부리기: 분할 팀이 5장을 두 묶음으로 나누고 1장을 뒷면으로 둔다. */
    SCHEME("꾀부리기"),
    /** 분배: 선택 팀이 한 묶음을 고르고, 뒷면을 공개해 나눠 가진다. */
    DISTRIBUTE("분배"),
    /** 환금: 각자 세트를 환금하거나 카드를 처분한다. */
    CASH_IN("환금"),
    /** 종료: 승리 확인 + 보물 보유 한도 확인. */
    END("종료");

    private final String korean;

    Phase(String korean) {
        this.korean = korean;
    }

    public String korean() {
        return korean;
    }
}
