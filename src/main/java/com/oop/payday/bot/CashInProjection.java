package com.oop.payday.bot;

import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.model.card.Card;

/**
 * 환금 계획의 <b>예상 결과</b>(로드맵 4.4). 행동 목록만이 아니라 이번 환금 단계가 끝났을 때
 * 벌 코인과 즉시 승리(ALPHA) 여부, 남는 카드를 함께 돌려줘 종반 판단을 세트 코인 근사가 아닌
 * 실제 실현 가치로 하게 한다.
 *
 * <p>{@code coinsGained} 은 환금 세트 코인 + 즉시 반응 도우미 보너스(CUCKOO/LEO +3, LUCKY +7) −
 * 저주 강제 처분 비용(2코인)의 합이다. VIPER 코인·리더 효과(CHUCK/WISE)처럼 엔진이 환금 단계
 * 종료 시 정산하는 항목은 근사에서 제외한다(분할/선택 종반 마진은 {@link BotCardEvaluator#projectedCashCoin}
 * 로 리더 보너스까지 본다).
 *
 * @param actions        실제 제출할 환금 행동 목록
 * @param coinsGained    이번 환금 단계로 예상되는 코인 증분(위 근사)
 * @param instantWin     ALPHA 즉시 승리가 성립하는지
 * @param remainingCards 환금/처분 뒤 보관 영역에 남을 카드(도우미 드로우 제외, 근사)
 */
record CashInProjection(List<CashInAction> actions, int coinsGained, boolean instantWin, List<Card> remainingCards) {

    CashInProjection {
        actions = List.copyOf(actions);
        remainingCards = List.copyOf(remainingCards);
    }
}
