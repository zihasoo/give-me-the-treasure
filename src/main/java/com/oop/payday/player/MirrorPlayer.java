package com.oop.payday.player;

import java.util.List;

import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.CashSink;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 클라이언트 측 플레이어 미러. 렌더링 전용이라 의사결정 메서드는 지원하지 않는다.
 * 게임 상태 갱신은 {@link com.oop.payday.net.ClientMirror#applyState} 가 수행한다.
 */
public final class MirrorPlayer extends Player {

    public MirrorPlayer(String name) {
        super(name);
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        throw new UnsupportedOperationException("클라이언트 측에서 호출 불가");
    }

    @Override
    public int decideChoice(ChoiceView view) {
        throw new UnsupportedOperationException("클라이언트 측에서 호출 불가");
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        throw new UnsupportedOperationException("클라이언트 측에서 호출 불가");
    }

    @Override
    public void beginCashIn(CashInContext snapshot, CashSink sink) {
        throw new UnsupportedOperationException("클라이언트 측에서 호출 불가");
    }

    @Override
    public boolean isBot() {
        return false;
    }
}
