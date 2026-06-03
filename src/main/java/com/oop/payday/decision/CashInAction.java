package com.oop.payday.decision;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 환금 단계에서 플레이어가 수행하는 한 가지 행동.
 * 규칙서 §6-3의 【A】환금 / 【B】처분 / 【C】도움 요청에 대응한다.
 */
public sealed interface CashInAction
        permits CashInAction.Cash, CashInAction.CashWithHelpers, CashInAction.Discard, CashInAction.UseHelper {

    /** 【A】 환금: 보관 카드로 세트를 만들어 코인을 얻는다. */
    record Cash(List<Card> cards) implements CashInAction {
        public Cash {
            cards = List.copyOf(cards);
        }
    }

    /** 환금과 그 세트에 반응하는 도우미 사용을 한 번에 처리한다. */
    record CashWithHelpers(List<Card> cards, List<HelperCard> helpers) implements CashInAction {
        public CashWithHelpers {
            cards = List.copyOf(cards);
            helpers = List.copyOf(helpers);
        }
    }

    /** 【B】 처분: 카드 1장을 버린다. (저주받은 그림은 2코인 필요 — M5) */
    record Discard(Card card) implements CashInAction {
    }

    /** 【C】 도움 요청: 자기 도우미 카드 1장을 앞면으로 뒤집어 사용한다. */
    record UseHelper(HelperCard helper, HelperCard copyTarget) implements CashInAction {
        public UseHelper(HelperCard helper) {
            this(helper, null);
        }
    }
}
