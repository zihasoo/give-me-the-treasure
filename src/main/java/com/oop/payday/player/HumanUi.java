package com.oop.payday.player;

import java.util.List;

import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 사람 플레이어가 의사결정이 필요할 때 UI에 입력을 요청하는 창구.
 *
 * <p>게임 스레드의 {@link HumanPlayer} 가 호출하며, 구현체(JavaFX 컨트롤러)는
 * 해당 입력 패널을 띄운 뒤 사용자가 행동을 마치면 {@code HumanPlayer.provideXxx} 로
 * 결과를 돌려준다. 구현체는 UI 갱신을 {@code Platform.runLater} 로 감싸야 한다.
 */
public interface HumanUi {

    void requestSplit(HumanPlayer player, List<Card> hand);

    void requestChoice(HumanPlayer player, ChoiceView view);

    void requestHelperSelection(HumanPlayer player, List<HelperCard> options, int chooseCount);

    void requestCashIn(HumanPlayer player, CashInContext context);
}
