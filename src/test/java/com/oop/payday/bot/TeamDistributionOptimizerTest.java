package com.oop.payday.bot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;

/**
 * 2v2 팀 분배(로드맵 4.5)가 세트 완성 카드를 한 멤버에게 몰고 저주를 처분 가능한 멤버로 보내는지 확인한다.
 */
final class TeamDistributionOptimizerTest {

    @Test
    void concentratesSetCompletingCardOnTheBuilder() {
        // 멤버0 이 RED1,RED2 로 색런을 짓는 중. RED3 는 멤버0 에게 몰아줘야 한다.
        List<List<Card>> memberHoldings = List.of(
                List.of(new TreasureCard(1, CardColor.RED, 1), new TreasureCard(2, CardColor.RED, 2)),
                List.of());
        List<Card> acquired = List.of(
                new TreasureCard(3, CardColor.RED, 3),
                new TreasureCard(4, CardColor.BLUE, 7));

        TeamDistribution distribution = TeamDistributionOptimizer.distribute(acquired, memberHoldings);

        boolean builderGotRun = distribution.byMember().get(0).stream()
                .anyMatch(c -> c instanceof TreasureCard t && t.color() == CardColor.RED && t.number() == 3);
        assertTrue(builderGotRun, "색런을 완성하는 RED3 는 그 빌더 멤버에게 가야 한다.");
    }

    @Test
    void routesCurseToMemberThatCanFreeDispose() {
        // 멤버0 은 숫자2 보물 보유(같은 숫자 세트로 저주 무료 처분 여지) → 저주2 를 멤버0 에게.
        List<List<Card>> memberHoldings = List.of(
                List.of(new TreasureCard(1, CardColor.RED, 2)),
                List.of(new TreasureCard(2, CardColor.BLUE, 5)));
        List<Card> acquired = List.of(new CursedCard(3, 2));

        TeamDistribution distribution = TeamDistributionOptimizer.distribute(acquired, memberHoldings);

        boolean disposerGotCurse = distribution.byMember().get(0).stream()
                .anyMatch(CursedCard.class::isInstance);
        assertTrue(disposerGotCurse, "저주는 같은 숫자 보물을 든(무료 처분 가능) 멤버로 보내야 한다.");
    }
}
