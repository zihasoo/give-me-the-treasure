package com.oop.payday.model.card;

/**
 * 슬쩍하기. 분배 단계에서 획득하는 즉시, 이 카드와 버림 더미를 모두 카드 더미에 합쳐
 * 섞은 뒤 그 팀이 카드 더미에서 1장을 뽑아 가져온다(규칙서 §3-1). 덱에 1장만 존재한다.
 * 세트에는 쓸 수 없다.
 */
public final class StealCard extends Card {

    public StealCard(int id) {
        super(id);
    }

    @Override
    public String displayName() {
        return "슬쩍하기";
    }
}
