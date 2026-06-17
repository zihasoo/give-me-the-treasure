package com.oop.payday.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;

/**
 * S7 이 S6 의 사람 상대 패인을 실제로 고쳤는지 고정하는 회귀 테스트.
 */
final class S7BotStrategyTest {

    /**
     * 사람 vs S6 2차 로그(2026-06-16) R3 재현. 분할자가 값진 카드를 <b>얇은 묶음(공개 1장)+뒷면</b>에 숨기고
     * <b>두꺼운 미끼 묶음(공개 3장, 저주 포함)</b>을 내민 상황이다. S6 는 공개 장수에 끌려 미끼를 집어
     * 굉장한 보물을 헌납했다(그 보물이 사람의 9·15코인 세트가 됐다). S7 은 적대적 뒷면 추론으로
     * 얇은 뒷면 묶음(index 0)을 집어 숨은 가치를 확보해야 한다.
     */
    @Test
    void prefersThinFaceDownBundleOverFatBaitBundle() {
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

        int chosen = new S7BotStrategy().decideChoice(context);

        assertEquals(0, chosen, "얇은 뒷면 묶음을 집어 숨은 보물을 확보해야 한다(R3 헌납 교정).");
    }

    /**
     * 저주 부채(②): 공개상 비슷한 두 묶음 중 한쪽에만 저주가 보일 때, S7 은 저주 없는 묶음을 집는다.
     * S6 는 저주를 0(중립)으로 봐 무차별이었다.
     */
    @Test
    void avoidsBundleWithVisibleCurse() {
        BundleView clean = new BundleView(List.of(new TreasureCard(1, CardColor.RED, 4)), false);
        BundleView cursed = new BundleView(List.of(
                new TreasureCard(2, CardColor.BLUE, 4),
                new CursedCard(3, 2)), false);
        ChoiceView view = new ChoiceView(List.of(clean, cursed));

        ChoiceContext context = new ChoiceContext(view, List.of(), 0, 0, 30, List.of(),
                List.of(), List.of(), null);

        int chosen = new S7BotStrategy().decideChoice(context);

        assertEquals(0, chosen, "저주가 보이는 묶음은 부채로 보고 피해야 한다.");
    }
}
