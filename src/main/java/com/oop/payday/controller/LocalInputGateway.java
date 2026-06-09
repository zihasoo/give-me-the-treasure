package com.oop.payday.controller;

import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.player.HumanPlayer;

/** 오프라인·호스트 모드: 인프로세스 {@link HumanPlayer} 에 직접 위임한다. */
public final class LocalInputGateway implements InputGateway {

    private final HumanPlayer player;

    public LocalInputGateway(HumanPlayer player) {
        this.player = player;
    }

    @Override public void provideSplit(SplitDecision decision) { player.provideSplit(decision); }
    @Override public void provideChoice(int index)              { player.provideChoice(index); }
    @Override public void provideHelpers(List<HelperCard> h)    { player.provideHelpers(h); }
    @Override public void provideDistribution(TeamDistribution d) { player.provideDistribution(d); }
    @Override public void submitCash(CashInAction action)       { player.submitCash(action); }
    @Override public void passCash()                            { player.passCash(); }
}
