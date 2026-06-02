package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;

/**
 * 선택 팀이 보는 한 묶음의 모습: 공개 카드들 + 뒷면 카드 유무.
 * 뒷면 카드의 정체는 알 수 없다(블러핑의 핵심).
 *
 * @param visibleCards 앞면으로 공개된 카드들
 * @param hasFaceDown  뒷면(비공개) 카드가 1장 있는지 여부
 */
public record BundleView(List<Card> visibleCards, boolean hasFaceDown) {

    public BundleView {
        visibleCards = List.copyOf(visibleCards);
    }

    /** 뒷면 포함 전체 장수. */
    public int size() {
        return visibleCards.size() + (hasFaceDown ? 1 : 0);
    }
}
