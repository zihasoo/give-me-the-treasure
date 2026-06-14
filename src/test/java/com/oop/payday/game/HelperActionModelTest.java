package com.oop.payday.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.oop.payday.bot.HeuristicBotStrategy;
import com.oop.payday.bot.S1BotStrategy;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.StealCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.Deck;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperCards;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperUseContext;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.Player;

final class HelperActionModelTest {

    @Test
    void smartBotPlansMultipleDisjointCashSets() {
        List<Card> holdings = List.of(
                new TreasureCard(300, CardColor.RED, 1),
                new TreasureCard(301, CardColor.RED, 2),
                new TreasureCard(302, CardColor.RED, 3),
                new TreasureCard(303, CardColor.BLUE, 5),
                new TreasureCard(304, CardColor.TEAL, 5));
        CashInContext context = new CashInContext(holdings, List.of(), List.of(), List.of(), 0, 10, 30);

        List<CashInAction> actions = new S1BotStrategy().planCashIn(context, 0);

        long cashActions = actions.stream()
                .filter(action -> action instanceof CashInAction.Cash
                        || action instanceof CashInAction.CashWithHelpers)
                .count();
        assertEquals(2, cashActions);
    }

    @Test
    void smartBotDiscardsCursedCardBeforeLowPotentialSpecialCard() {
        CursedCard cursed = new CursedCard(400, 2);
        StealCard steal = new StealCard(401);
        CashInContext context = new CashInContext(List.of(cursed, steal), List.of(), List.of(), List.of(), 0, 1, 30);

        List<CashInAction> actions = new S1BotStrategy().planCashIn(context, 0);

        CashInAction.Discard discard = assertInstanceOf(CashInAction.Discard.class, actions.get(0));
        assertSame(cursed, discard.card());
    }

    @Test
    void botAttachesCashReactionHelperToCashAction() {
        HelperCard lucky = helper(HelperKind.LUCKY);
        List<Card> sameColorRun = List.of(
                new TreasureCard(100, CardColor.RED, 1),
                new TreasureCard(101, CardColor.RED, 2),
                new TreasureCard(102, CardColor.RED, 3),
                new TreasureCard(103, CardColor.RED, 4),
                new TreasureCard(104, CardColor.RED, 5));
        CashInContext context = new CashInContext(sameColorRun, List.of(lucky), List.of(), List.of(), 0, 5, 30);

        List<CashInAction> actions = new HeuristicBotStrategy().planCashIn(context, 0);

        CashInAction.CashWithHelpers action = assertInstanceOf(CashInAction.CashWithHelpers.class, actions.get(0));
        assertEquals(sameColorRun.size(), action.cards().size());
        assertEquals(List.of(lucky), action.helpers());
    }

    @Test
    void crocBrothersActionKeepsSelectedCopyTarget() {
        HelperCard croc = helper(HelperKind.CROC_BROTHERS);
        HelperCard tusker = helper(HelperKind.TUSKER);
        CashInContext context = new CashInContext(List.of(), List.of(croc), List.of(tusker), List.of(), 0, 5, 30);

        List<CashInAction> actions = new HeuristicBotStrategy().planCashIn(context, 0);

        CashInAction.UseHelper action = assertInstanceOf(CashInAction.UseHelper.class, actions.get(0));
        assertSame(croc, action.helper());
        assertSame(tusker, action.copyTarget());
    }

    @Test
    void crocBrothersCopyingTuskerSuspendsHoldLimit() {
        HelperCard croc = helper(HelperKind.CROC_BROTHERS);
        HelperCard tusker = helper(HelperKind.TUSKER);
        Player player = BotPlayer.test(new HeuristicBotStrategy());
        Team team = new Team("테스트 팀", List.of(player));
        Team opponent = new Team("상대 팀", List.of(BotPlayer.test(new HeuristicBotStrategy())));
        HelperUseContext context = new HelperUseContext(
                player, team, opponent, new Deck(new Random(3)), null, List.of(tusker), tusker);

        croc.use(context);

        assertTrue(context.holdLimitSuspended());
    }

    @Test
    void dougDiscardsOnlySelectedNonCursedCards() {
        HelperCard doug = helper(HelperKind.DOUG);
        Player player = BotPlayer.test(new HeuristicBotStrategy());
        TreasureCard keep1 = new TreasureCard(200, CardColor.BLUE, 3);
        TreasureCard drop1 = new TreasureCard(201, CardColor.RED, 4);
        TreasureCard drop2 = new TreasureCard(202, CardColor.YELLOW, 5);
        TreasureCard keep2 = new TreasureCard(203, CardColor.TEAL, 6);
        player.receive(keep1);
        player.receive(drop1);
        player.receive(drop2);
        player.receive(keep2);
        player.receiveHelpers(List.of(doug));

        Team team = new Team("팀", List.of(player));
        Team opponent = new Team("상대", List.of(BotPlayer.test(new HeuristicBotStrategy())));
        HelperUseContext context = new HelperUseContext(
                player, team, opponent, new Deck(new Random(5)), null, List.of(), null, List.of(drop1, drop2));

        doug.use(context);

        assertEquals(4, player.holdingCount(), "버린 만큼 다시 뽑으므로 장수는 유지된다.");
        assertTrue(player.holdings().contains(keep1) && player.holdings().contains(keep2),
                "선택하지 않은 카드는 그대로 남는다.");
        assertFalse(player.holdings().contains(drop1) || player.holdings().contains(drop2),
                "선택한 카드만 버려진다.");
    }

    private static HelperCard helper(HelperKind kind) {
        return HelperCards.shuffledDeck(new Random(7)).stream()
                .filter(helper -> helper.kind() == kind)
                .findFirst()
                .orElseThrow();
    }
}
