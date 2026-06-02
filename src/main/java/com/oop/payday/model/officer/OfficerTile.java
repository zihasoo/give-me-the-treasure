package com.oop.payday.model.officer;

/**
 * 간부 타일 4종. 리더 토큰이 올라간 플레이어에게만 효과가 적용된다.
 */
public enum OfficerTile implements LeaderEffect {

    FLANKY("플랭키", "보물 보유 한도 +1") {
        @Override
        public int holdLimitBonus(LeaderContext context) {
            return context.enabled() ? 1 : 0;
        }
    },

    CHUCK("척", "한 라운드에 2회 이상 환금하면 1코인") {
        @Override
        public int bonusAfterCashPhase(LeaderContext context) {
            return context.enabled() && context.teamCashCountThisRound() >= 2 ? 1 : 0;
        }
    },

    WISE("와이즈", "환금 단계 종료 시 상대가 저주 2장 이상이면 1코인") {
        @Override
        public int bonusAfterCashPhase(LeaderContext context) {
            return context.enabled() && context.opponentCursedCount() >= 2 ? 1 : 0;
        }
    },

    JOE("죠", "6코인 이상 세트를 환금하면 1코인") {
        @Override
        public int bonusAfterCash(LeaderContext context) {
            return context.enabled() && context.cashedSet() != null && context.cashedSet().coin() >= 6 ? 1 : 0;
        }
    };

    private final String korean;
    private final String effectText;

    OfficerTile(String korean, String effectText) {
        this.korean = korean;
        this.effectText = effectText;
    }

    public String korean() {
        return korean;
    }

    public String effectText() {
        return effectText;
    }
}
