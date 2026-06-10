package com.oop.payday.net;

import java.util.List;

import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.game.GameListener;
import com.oop.payday.game.Phase;
import com.oop.payday.game.Team;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * 두 {@link GameListener}(호스트 컨트롤러 + 네트워크 브로드캐스터)에게 이벤트를 분배한다.
 * {@link #awaitAnimations()} 는 호스트 컨트롤러에만 위임한다(브로드캐스터는 no-op).
 */
public final class FanOutGameListener implements GameListener {

    private final GameListener primary;   // 호스트 GameBoardController
    private final GameListener secondary; // NetworkBroadcaster

    public FanOutGameListener(GameListener primary, GameListener secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        primary.onPhaseChanged(phase, round, splitTeam);
        secondary.onPhaseChanged(phase, round, splitTeam);
    }
    @Override public void onGameSetup(List<Player> players) {
        primary.onGameSetup(players);
        secondary.onGameSetup(players);
    }
    @Override public void onPlayerSetup(Player player) {
        primary.onPlayerSetup(player);
        secondary.onPlayerSetup(player);
    }
    @Override public void onHandDealt(Player splitter, List<Card> hand) {
        primary.onHandDealt(splitter, hand);
        secondary.onHandDealt(splitter, hand);
    }
    @Override public void onChoiceReady(BundlePair bundles) {
        primary.onChoiceReady(bundles);
        secondary.onChoiceReady(bundles);
    }
    @Override public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        primary.onDistributed(chosenIndex, chooseTeam, chooseCards, splitTeam, splitCards);
        secondary.onDistributed(chosenIndex, chooseTeam, chooseCards, splitTeam, splitCards);
    }
    @Override public void onCashIn(Player player, TreasureSet set) {
        primary.onCashIn(player, set);
        secondary.onCashIn(player, set);
    }
    @Override public void onCashTurn(Player player, CashInContext snapshot) {
        primary.onCashTurn(player, snapshot);
        secondary.onCashTurn(player, snapshot);
    }
    @Override public void onCashDone(Player player) {
        primary.onCashDone(player);
        secondary.onCashDone(player);
    }
    @Override public void onDiscard(Player player, Card card) {
        primary.onDiscard(player, card);
        secondary.onDiscard(player, card);
    }
    @Override public void onHelperUsed(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        primary.onHelperUsed(player, helper, message, drawn, discarded);
        secondary.onHelperUsed(player, helper, message, drawn, discarded);
    }
    @Override public void onForcedDiscard(Player player, List<Card> cards) {
        primary.onForcedDiscard(player, cards);
        secondary.onForcedDiscard(player, cards);
    }
    @Override public void onCoinsChanged(Team team, int delta) {
        primary.onCoinsChanged(team, delta);
        secondary.onCoinsChanged(team, delta);
    }
    @Override public void onRoundEnd(int round) {
        primary.onRoundEnd(round);
        secondary.onRoundEnd(round);
    }
    @Override public void onGameOver(Team winner) {
        primary.onGameOver(winner);
        secondary.onGameOver(winner);
    }
    @Override public void onMessage(String message) {
        primary.onMessage(message);
        secondary.onMessage(message);
    }
    @Override public void onStealActivated(Player player, Card drawnCard) {
        primary.onStealActivated(player, drawnCard);
        secondary.onStealActivated(player, drawnCard);
    }
    @Override public void onRequestSplit(Player player, List<Card> hand) {
        primary.onRequestSplit(player, hand);
        secondary.onRequestSplit(player, hand);
    }
    @Override public void onRequestChoice(Player player, ChoiceView view) {
        primary.onRequestChoice(player, view);
        secondary.onRequestChoice(player, view);
    }
    @Override public void onRequestHelpers(Player player, List<HelperCard> options, int chooseCount) {
        primary.onRequestHelpers(player, options, chooseCount);
        secondary.onRequestHelpers(player, options, chooseCount);
    }
    @Override public void onRequestTeamDistribution(Player leader, Team team, List<Card> acquired) {
        primary.onRequestTeamDistribution(leader, team, acquired);
        secondary.onRequestTeamDistribution(leader, team, acquired);
    }

    /** 호스트 컨트롤러의 애니메이션만 대기한다. 브로드캐스터는 no-op. */
    @Override public void awaitAnimations() {
        primary.awaitAnimations();
    }
}
