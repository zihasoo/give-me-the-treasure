package com.oop.payday.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.card.WildCard;
import com.oop.payday.model.set.SetEvaluator;
import com.oop.payday.model.set.TreasureSet;

/**
 * 공개 정보 카드 카운팅(로드맵 4.2)이 카드 위치·생존·와일드·성장 턴을 올바르게 보는지 확인한다.
 */
final class PublicCardTrackerTest {

    @Test
    void tracksCardLocations() {
        List<Card> mine = List.of(new TreasureCard(1, CardColor.RED, 1));
        List<Card> opp = List.of(new TreasureCard(2, CardColor.BLUE, 2));
        List<Card> discard = List.of(new WildCard(99), new TreasureCard(3, CardColor.YELLOW, 3));
        PublicCardTracker tracker = new PublicCardTracker(mine, opp, discard);

        assertEquals(PublicCardTracker.Location.MINE, tracker.locationOf(CardColor.RED, 1));
        assertEquals(PublicCardTracker.Location.OPP, tracker.locationOf(CardColor.BLUE, 2));
        assertEquals(PublicCardTracker.Location.DISCARD, tracker.locationOf(CardColor.YELLOW, 3));
        assertEquals(PublicCardTracker.Location.UNKNOWN, tracker.locationOf(CardColor.TEAL, 5));
    }

    @Test
    void distinguishesLiveFromDeadCards() {
        PublicCardTracker tracker = new PublicCardTracker(
                List.of(new TreasureCard(1, CardColor.RED, 1)),
                List.of(new TreasureCard(2, CardColor.BLUE, 2)),
                List.of(new TreasureCard(3, CardColor.YELLOW, 3)));

        assertTrue(tracker.isLive(CardColor.YELLOW, 3), "버림 더미 카드는 슬쩍하기로 살아 있다.");
        assertTrue(tracker.isLive(CardColor.TEAL, 5), "미지 카드는 살아 있다.");
        assertFalse(tracker.isLive(CardColor.BLUE, 2), "상대 손패 카드는 (당장) 죽었다.");
        assertFalse(tracker.isLive(CardColor.RED, 1), "내 보관 카드는 목표가 아니다.");
    }

    @Test
    void detectsWildInDiscardAndCountsUnknown() {
        PublicCardTracker tracker = new PublicCardTracker(
                List.of(new TreasureCard(1, CardColor.RED, 1)),
                List.of(new TreasureCard(2, CardColor.BLUE, 2)),
                List.of(new WildCard(99), new TreasureCard(3, CardColor.YELLOW, 3)));

        assertTrue(tracker.wildInDiscard(), "버림 더미 와일드를 잡아야 한다(JUNK_DEALER 가치).");
        assertEquals(PublicCardTracker.TOTAL_TREASURE - 3, tracker.unknownTreasureCount(),
                "공개된 보물 3장을 뺀 나머지가 미지여야 한다.");
    }

    @Test
    void growthTurnsUseLiveLocation() {
        // RED1,2,3 색런(3장). 키울 카드는 RED4 하나. RED4 가 버림 더미면 빨리 회수(1.5턴).
        List<Card> mine = List.of(
                new TreasureCard(1, CardColor.RED, 1),
                new TreasureCard(2, CardColor.RED, 2),
                new TreasureCard(3, CardColor.RED, 3));
        List<Card> discard = List.of(new TreasureCard(4, CardColor.RED, 4));
        PublicCardTracker tracker = new PublicCardTracker(mine, List.of(), discard);
        TreasureSet set = SetEvaluator.evaluate(mine).orElseThrow();

        assertEquals(1.5, tracker.expectedTurnsToGrow(set), 1e-9,
                "키울 카드가 버림 더미에 살아 있으면 예상 턴이 낮아야 한다.");
    }
}
