package com.oop.payday.model.helper;

import java.util.List;

import com.oop.payday.game.Team;
import com.oop.payday.model.Deck;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * 도우미 카드 효과가 읽고 변경할 수 있는 환금 단계 상황.
 */
public final class HelperUseContext {

    private final Player player;
    private final Team team;
    private final Team opponent;
    private final Deck deck;
    private final TreasureSet lastCashedSet;
    private final List<HelperCard> usedHelpers;
    private final HelperCard copyTarget;
    private final List<Card> selectedCards;

    private String message;
    private Team instantWinner;
    private boolean cashBlocked;
    private boolean holdLimitSuspended;

    public HelperUseContext(Player player, Team team, Team opponent, Deck deck,
            TreasureSet lastCashedSet, List<HelperCard> usedHelpers) {
        this(player, team, opponent, deck, lastCashedSet, usedHelpers, null, List.of());
    }

    public HelperUseContext(Player player, Team team, Team opponent, Deck deck,
            TreasureSet lastCashedSet, List<HelperCard> usedHelpers, HelperCard copyTarget) {
        this(player, team, opponent, deck, lastCashedSet, usedHelpers, copyTarget, List.of());
    }

    public HelperUseContext(Player player, Team team, Team opponent, Deck deck,
            TreasureSet lastCashedSet, List<HelperCard> usedHelpers, HelperCard copyTarget,
            List<Card> selectedCards) {
        this.player = player;
        this.team = team;
        this.opponent = opponent;
        this.deck = deck;
        this.lastCashedSet = lastCashedSet;
        this.usedHelpers = usedHelpers;
        this.copyTarget = copyTarget;
        this.selectedCards = List.copyOf(selectedCards);
    }

    public Player player() {
        return player;
    }

    public Team team() {
        return team;
    }

    public Team opponent() {
        return opponent;
    }

    public Deck deck() {
        return deck;
    }

    public TreasureSet lastCashedSet() {
        return lastCashedSet;
    }

    public List<HelperCard> usedHelpers() {
        return usedHelpers;
    }

    public HelperCard copyTarget() {
        return copyTarget;
    }

    /** 카드 선택이 필요한 도우미(샛길의 더그)가 버릴 카드. 그 외엔 빈 목록. */
    public List<Card> selectedCards() {
        return selectedCards;
    }

    public void addCoins(int amount) {
        team.addCoins(amount);
    }

    public void discard(Card card) {
        if (player.remove(card)) {
            deck.discard(card);
        }
    }

    public Card draw() {
        Card drawn = deck.draw();
        if (drawn != null) {
            player.receive(drawn);
        }
        return drawn;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public void setInstantWinner(Team winner) {
        this.instantWinner = winner;
    }

    public Team instantWinner() {
        return instantWinner;
    }

    public void blockCashForRound() {
        this.cashBlocked = true;
    }

    public boolean cashBlocked() {
        return cashBlocked;
    }

    public void suspendHoldLimitForRound() {
        this.holdLimitSuspended = true;
    }

    public boolean holdLimitSuspended() {
        return holdLimitSuspended;
    }
}
