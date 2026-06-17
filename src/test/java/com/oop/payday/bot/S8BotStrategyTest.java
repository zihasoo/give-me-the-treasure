package com.oop.payday.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.card.WildCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperCards;
import com.oop.payday.model.helper.HelperKind;

/**
 * S8 의 새 평가 축(실현 코인 종반 마진·ALPHA 즉승·와일드 확보)과, S7 에서 계승한 교정이 S8 에서도
 * 유지되는지 고정하는 회귀 테스트.
 */
final class S8BotStrategyTest {

    /**
     * 로드맵 4.4 핵심: ALPHA 즉승을 놓치지 않는다. 숫자 1 4장을 완성하는 묶음(즉승)과, 코인은 더 큰
     * 15코인 색런을 완성하는 묶음 중, ALPHA 를 든 S8 은 코인이 낮아도 즉승 묶음을 택해야 한다.
     * (세트 코인만 보던 S7 근사는 15코인 묶음을 골라 즉승을 놓쳤다.)
     */
    @Test
    void takesAlphaInstantWinOverHigherCoinSet() {
        BundleView alphaBundle = new BundleView(List.of(new TreasureCard(1, CardColor.TEAL, 1)), false);
        BundleView richRun = new BundleView(List.of(
                new TreasureCard(2, CardColor.RED, 2),
                new TreasureCard(3, CardColor.RED, 3),
                new TreasureCard(4, CardColor.RED, 4)), false);
        ChoiceView view = new ChoiceView(List.of(alphaBundle, richRun));

        List<Card> holdings = List.of(
                new TreasureCard(10, CardColor.RED, 1),
                new TreasureCard(11, CardColor.YELLOW, 1),
                new TreasureCard(12, CardColor.BLUE, 1));
        ChoiceContext context = new ChoiceContext(view, holdings, 0, 0, 30, List.of(),
                List.of(), List.of(helper(HelperKind.ALPHA)), null);

        int chosen = new S8BotStrategy().decideChoice(context);

        assertEquals(0, chosen, "ALPHA 즉승 묶음(숫자1 4장)을 코인 큰 묶음보다 먼저 택해야 한다.");
    }

    /**
     * 와일드(굉장한 보물)는 어디 있어도 확보한다. 보이는 와일드 묶음과 평범한 묶음 중 와일드 묶음을 집어야 한다.
     */
    @Test
    void grabsVisibleWild() {
        BundleView wildBundle = new BundleView(List.of(new WildCard(1)), false);
        BundleView plain = new BundleView(List.of(new TreasureCard(2, CardColor.RED, 4)), false);
        ChoiceView view = new ChoiceView(List.of(wildBundle, plain));

        ChoiceContext context = new ChoiceContext(view, List.of(), 0, 0, 30, List.of(),
                List.of(), List.of(), null);

        int chosen = new S8BotStrategy().decideChoice(context);

        assertEquals(0, chosen, "보이는 와일드는 반드시 확보해야 한다.");
    }

    /**
     * S7 계승: 얇은 묶음(공개 1장)+뒷면 vs 두꺼운 미끼 묶음(저주 포함)에서 적대적 뒷면 추론으로
     * 얇은 뒷면 묶음을 집어 숨은 가치를 확보한다(S8 도 이 교정을 잃지 않아야 한다).
     */
    @Test
    void keepsThinFaceDownInferenceFromS7() {
        BundleView thinHidden = new BundleView(List.of(new TreasureCard(1, CardColor.TEAL, 6)), true);
        BundleView fatBait = new BundleView(List.of(
                new TreasureCard(2, CardColor.RED, 3),
                new TreasureCard(3, CardColor.BLUE, 6),
                new CursedCard(4, 5)), false);
        ChoiceView view = new ChoiceView(List.of(thinHidden, fatBait));

        List<Card> holdings = List.of(
                new TreasureCard(10, CardColor.YELLOW, 2),
                new TreasureCard(11, CardColor.RED, 7),
                new TreasureCard(12, CardColor.YELLOW, 1),
                new TreasureCard(13, CardColor.TEAL, 3));
        ChoiceContext context = new ChoiceContext(view, holdings, 2, 0, 30, List.of(),
                List.of(), List.of(), null);

        int chosen = new S8BotStrategy().decideChoice(context);

        assertEquals(0, chosen, "얇은 뒷면 묶음을 집어 숨은 보물을 확보해야 한다(S7 교정 계승).");
    }

    /** S7 계승: 공개상 비슷한 두 묶음 중 저주가 보이는 묶음은 부채로 보고 피한다. */
    @Test
    void keepsCurseAversionFromS7() {
        BundleView clean = new BundleView(List.of(new TreasureCard(1, CardColor.RED, 4)), false);
        BundleView cursed = new BundleView(List.of(
                new TreasureCard(2, CardColor.BLUE, 4),
                new CursedCard(3, 2)), false);
        ChoiceView view = new ChoiceView(List.of(clean, cursed));

        ChoiceContext context = new ChoiceContext(view, List.of(), 0, 0, 30, List.of(),
                List.of(), List.of(), null);

        int chosen = new S8BotStrategy().decideChoice(context);

        assertEquals(0, chosen, "저주가 보이는 묶음은 부채로 보고 피해야 한다(S7 교정 계승).");
    }

    /**
     * 2026-06-17 10:16:43 R6 회귀: S8 이 크록 형제로 사용된 투스커를 복사해 한도 무시를 얻고도,
     * 이어서 저주를 2코인 내고 처분했다. 크록->투스커도 투스커 사용으로 보아 강제 처분을 붙이지 않아야 한다.
     */
    @Test
    void crocCopyingTuskerDoesNotDiscardCurseForLimit() {
        HelperCard croc = helper(HelperKind.CROC_BROTHERS);
        HelperCard usedTusker = helper(HelperKind.TUSKER);
        List<Card> holdings = List.of(
                new TreasureCard(1, CardColor.BLUE, 2),
                new TreasureCard(2, CardColor.BLUE, 1),
                new TreasureCard(3, CardColor.YELLOW, 7),
                new TreasureCard(4, CardColor.RED, 5),
                new TreasureCard(5, CardColor.BLUE, 5),
                new CursedCard(6, 5),
                new TreasureCard(7, CardColor.YELLOW, 5),
                new CursedCard(8, 2),
                new TreasureCard(9, CardColor.RED, 7),
                new WildCard(10),
                new TreasureCard(11, CardColor.YELLOW, 3),
                new TreasureCard(12, CardColor.YELLOW, 2));
        CashInContext context = new CashInContext(holdings, List.of(croc), List.of(usedTusker),
                List.of(), 10, 6, 30, List.of());

        List<CashInAction> actions = new S8BotStrategy().planCashIn(context, 18);

        assertTrue(actions.stream().anyMatch(action -> action instanceof CashInAction.UseHelper use
                        && use.helper().kind() == HelperKind.CROC_BROTHERS
                        && use.copyTarget() != null
                        && use.copyTarget().kind() == HelperKind.TUSKER),
                "한도 초과 구제용으로 크록이 사용된 투스커를 복사해야 한다.");
        assertFalse(actions.stream().anyMatch(action -> action instanceof CashInAction.Discard discard
                        && discard.card() instanceof CursedCard),
                "크록->투스커로 이번 라운드 한도가 무시되면 저주를 2코인 내고 처분하지 않아야 한다.");
    }

    private static HelperCard helper(HelperKind kind) {
        return HelperCards.shuffledDeck(new Random(7)).stream()
                .filter(h -> h.kind() == kind)
                .findFirst()
                .orElseThrow();
    }
}
