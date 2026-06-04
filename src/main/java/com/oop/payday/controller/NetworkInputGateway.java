package com.oop.payday.controller;

import java.io.IOException;
import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.NetMessage;

/** 클라이언트 모드: 결정을 id 직렬화해 호스트로 전송한다. */
public final class NetworkInputGateway implements InputGateway {

    private final GameClient client;

    public NetworkInputGateway(GameClient client) {
        this.client = client;
    }

    @Override
    public void provideSplit(SplitDecision decision) {
        send(new NetMessage.SplitDecision(
                ids(decision.bundleA()),
                ids(decision.bundleB()),
                decision.faceDownCard().id()));
    }

    @Override
    public void provideChoice(int index) {
        send(new NetMessage.ChoiceDecision(index));
    }

    @Override
    public void provideHelpers(List<HelperCard> helpers) {
        send(new NetMessage.HelpersDecision(helpers.stream().map(HelperCard::id).toList()));
    }

    @Override
    public void submitCash(CashInAction action) {
        NetMessage msg = switch (action) {
            case CashInAction.Cash c ->
                new NetMessage.CashAction("CASH", ids(c.cards()), null, null, List.of());
            case CashInAction.CashWithHelpers c ->
                new NetMessage.CashAction("CASH_WITH_HELPERS", ids(c.cards()),
                        null, null,
                        c.helpers().stream().map(HelperCard::id).toList());
            case CashInAction.Discard d ->
                new NetMessage.CashAction("DISCARD", List.of(d.card().id()), null, null, List.of());
            case CashInAction.UseHelper u ->
                new NetMessage.CashAction("USE_HELPER", List.of(),
                        u.helper().id(),
                        u.copyTarget() != null ? u.copyTarget().id() : null,
                        ids(u.selectedCards()));
        };
        send(msg);
    }

    @Override
    public void passCash() {
        send(new NetMessage.CashPass());
    }

    private static List<Integer> ids(List<? extends Card> cards) {
        return cards.stream().map(Card::id).toList();
    }

    private void send(NetMessage msg) {
        try {
            client.send(msg);
        } catch (IOException e) {
            // 연결이 끊기면 reader 루프가 감지해 처리 — 여기서는 조용히 무시
        }
    }
}
