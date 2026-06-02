package com.oop.payday.model.officer;

/**
 * 간부 타일별 리더 효과 계약. 각 간부 enum 상수가 이 전략을 구현한다.
 */
public interface LeaderEffect {

    default int holdLimitBonus(LeaderContext context) {
        return 0;
    }

    default int bonusAfterCash(LeaderContext context) {
        return 0;
    }

    default int bonusAfterCashPhase(LeaderContext context) {
        return 0;
    }
}
