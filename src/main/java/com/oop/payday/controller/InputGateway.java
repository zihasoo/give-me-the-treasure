package com.oop.payday.controller;

import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.helper.HelperCard;

/**
 * 플레이어 의사결정 제출 창구. 오프라인·호스트·클라이언트 모드를 동일하게 추상화한다.
 */
public interface InputGateway {
    void provideSplit(SplitDecision decision);
    void provideChoice(int index);
    void provideHelpers(List<HelperCard> helpers);
    void provideDistribution(TeamDistribution distribution);
    void submitCash(CashInAction action);
    void passCash();
}
