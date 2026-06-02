package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;

/**
 * 꾀부리기 단계의 분할 팀 결정: 5장을 두 묶음("2+3" 또는 "1+4")으로 나누고
 * 그중 정확히 1장을 뒷면으로 둔다.
 *
 * @param bundleA      첫 번째 묶음
 * @param bundleB      두 번째 묶음
 * @param faceDownCard 뒷면(비공개)으로 둘 카드 — bundleA 또는 bundleB 안의 1장
 */
public record SplitDecision(List<Card> bundleA, List<Card> bundleB, Card faceDownCard) {

    public SplitDecision {
        bundleA = List.copyOf(bundleA);
        bundleB = List.copyOf(bundleB);
    }

    /** 규칙(총 5장, 2+3 또는 1+4, 뒷면 카드가 한 묶음에 포함)을 만족하는지 검증. */
    public boolean isValid() {
        int a = bundleA.size();
        int b = bundleB.size();
        if (a + b != 5) {
            return false;
        }
        boolean sizeOk = (a == 2 && b == 3) || (a == 3 && b == 2)
                || (a == 1 && b == 4) || (a == 4 && b == 1);
        boolean faceDownOk = bundleA.contains(faceDownCard) || bundleB.contains(faceDownCard);
        return sizeOk && faceDownOk;
    }
}
