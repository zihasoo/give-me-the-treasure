package com.oop.payday.bot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.TreasureCard;

/**
 * 상대 선택 확률 모델이 성향(prior 가중치)에 따라 같은 두 묶음을 다르게 평가하는지 확인한다(로드맵 4.3).
 */
final class OpponentModelTest {

    /** 코인 많은(세트 6코인) 묶음 A vs 카드 많은(세트 불가) 묶음 B. */
    private static final List<Card> coinHeavy = List.of(
            new TreasureCard(1, CardColor.RED, 1),
            new TreasureCard(2, CardColor.RED, 2),
            new TreasureCard(3, CardColor.RED, 3)); // 색런 3장 = 6코인, 3장
    private static final List<Card> materialHeavy = List.of(
            new TreasureCard(4, CardColor.BLUE, 2),
            new TreasureCard(5, CardColor.TEAL, 4),
            new TreasureCard(6, CardColor.YELLOW, 6),
            new TreasureCard(7, CardColor.RED, 7)); // 세트 없음(0코인), 4장

    @Test
    void coinFocusedPriorPrefersCoinBundle() {
        OpponentModel coinFocused = new OpponentModel(100, 0, 0, 600, 0, 100, 200.0);
        double pA = coinFocused.pTakesA(coinHeavy, false, false, materialHeavy, false, false, List.of());
        assertTrue(pA > 0.5, "코인 중시 성향은 코인 큰 묶음(A)을 더 가져간다. pA=" + pA);
    }

    @Test
    void materialFocusedPriorPrefersMaterialBundle() {
        OpponentModel materialFocused = new OpponentModel(0, 70, 0, 600, 0, 100, 200.0);
        double pA = materialFocused.pTakesA(coinHeavy, false, false, materialHeavy, false, false, List.of());
        assertTrue(pA < 0.5, "물량 중시 성향은 카드 많은 묶음(B)을 더 가져간다. pA=" + pA);
    }

    @Test
    void priorChangesTheSameDecision() {
        OpponentModel coinFocused = new OpponentModel(100, 0, 0, 600, 0, 100, 200.0);
        OpponentModel materialFocused = new OpponentModel(0, 70, 0, 600, 0, 100, 200.0);
        double coinPA = coinFocused.pTakesA(coinHeavy, false, false, materialHeavy, false, false, List.of());
        double materialPA = materialFocused.pTakesA(coinHeavy, false, false, materialHeavy, false, false, List.of());
        assertTrue(coinPA > materialPA, "같은 두 묶음이라도 성향에 따라 선택 확률이 달라져야 한다.");
    }
}
