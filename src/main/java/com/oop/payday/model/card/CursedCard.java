package com.oop.payday.model.card;

/**
 * 저주받은 그림. 숫자 2~6 이 하나씩 있는 5장(규칙서 §3-1).
 *
 * <ul>
 *   <li>세트에 넣을 수 없다.</li>
 *   <li>처분하려면 2코인을 지불해야 한다.</li>
 *   <li>단, 자신의 숫자와 같은 숫자의 보물을 포함한 세트를 환금할 때는
 *       코인 없이 무료로 처분할 수 있다(숫자는 이 무료 처분 조건에만 쓰인다).</li>
 * </ul>
 */
public final class CursedCard extends Card {

    public static final int MIN_NUMBER = 2;
    public static final int MAX_NUMBER = 6;

    private final int number;

    public CursedCard(int id, int number) {
        super(id);
        if (number < MIN_NUMBER || number > MAX_NUMBER) {
            throw new IllegalArgumentException("저주받은 그림 숫자는 2~6 이어야 합니다: " + number);
        }
        this.number = number;
    }

    public int number() {
        return number;
    }

    @Override
    public String displayName() {
        return "저주받은 그림 " + number;
    }
}
