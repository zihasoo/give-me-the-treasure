package com.oop.payday.model.set;

/**
 * 세트 종류와 환금표(규칙서 §7). 각 상수가 장수→코인 계산식을 직접 보유한다(전략 패턴).
 *
 * <pre>
 * 장수 | 같은 숫자 | 연속 | 연속+같은색
 *  2   |    2     | -    |   -
 *  3   |    5     | 4    |   6
 *  4   |   10     | 6    |   9
 *  5   |    -     | 10   |  15
 * </pre>
 */
public enum SetType {

    SAME_NUMBER("같은 숫자") {
        @Override
        public int coin(int size) {
            return switch (size) {
                case 2 -> 2;
                case 3 -> 5;
                case 4 -> 10;
                default -> INVALID;
            };
        }
    },

    RUN("연속된 숫자") {
        @Override
        public int coin(int size) {
            return switch (size) {
                case 3 -> 4;
                case 4 -> 6;
                case 5 -> 10;
                default -> INVALID;
            };
        }
    },

    RUN_SAME_COLOR("연속 + 같은 색") {
        @Override
        public int coin(int size) {
            return switch (size) {
                case 3 -> 6;
                case 4 -> 9;
                case 5 -> 15;
                default -> INVALID;
            };
        }
    };

    /** 해당 장수로 환금이 불가능함을 나타내는 코인값. */
    public static final int INVALID = -1;

    private final String korean;

    SetType(String korean) {
        this.korean = korean;
    }

    /** 주어진 장수의 코인값. 환금 불가한 장수면 {@link #INVALID}. */
    public abstract int coin(int size);

    public boolean isValidSize(int size) {
        return coin(size) != INVALID;
    }

    public String korean() {
        return korean;
    }
}
