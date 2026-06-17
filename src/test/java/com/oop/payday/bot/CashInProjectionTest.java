package com.oop.payday.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.oop.payday.decision.CashInContext;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperCards;
import com.oop.payday.model.helper.HelperKind;

/**
 * 환금 결과 평가 API(로드맵 4.4)가 실현 코인과 ALPHA 즉승을 올바르게 반환하는지 확인한다.
 */
final class CashInProjectionTest {

    @Test
    void projectsCoinsIncludingLuckyReactionBonus() {
        List<Card> sameColorRun = List.of(
                new TreasureCard(1, CardColor.RED, 1),
                new TreasureCard(2, CardColor.RED, 2),
                new TreasureCard(3, CardColor.RED, 3),
                new TreasureCard(4, CardColor.RED, 4),
                new TreasureCard(5, CardColor.RED, 5));
        CashInContext context = new CashInContext(sameColorRun, List.of(helper(HelperKind.LUCKY)),
                List.of(), List.of(), 0, 5, 30, List.of());

        CashInProjection projection = CashInPlanOptimizer.project(context, 0, CashInPlanOptimizer.Tuning.S8);

        assertEquals(22, projection.coinsGained(), "5장 색런 15코인 + LUCKY +7 = 22코인이어야 한다.");
        assertTrue(!projection.instantWin(), "LUCKY 만으로는 즉승이 아니다.");
    }

    @Test
    void flagsAlphaInstantWin() {
        List<Card> fourOnes = List.of(
                new TreasureCard(1, CardColor.RED, 1),
                new TreasureCard(2, CardColor.BLUE, 1),
                new TreasureCard(3, CardColor.YELLOW, 1),
                new TreasureCard(4, CardColor.TEAL, 1));
        CashInContext context = new CashInContext(fourOnes, List.of(helper(HelperKind.ALPHA)),
                List.of(), List.of(), 0, 5, 10, List.of());

        CashInProjection projection = CashInPlanOptimizer.project(context, 0, CashInPlanOptimizer.Tuning.S8);

        assertTrue(projection.instantWin(), "숫자1 4장 + ALPHA 는 즉승이어야 한다.");
    }

    private static HelperCard helper(HelperKind kind) {
        return HelperCards.shuffledDeck(new Random(7)).stream()
                .filter(h -> h.kind() == kind)
                .findFirst()
                .orElseThrow();
    }
}
