package com.oop.payday.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.Deck;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.StealCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperCards;
import com.oop.payday.model.helper.HelperUseContext;
import com.oop.payday.model.officer.LeaderContext;
import com.oop.payday.model.officer.OfficerTile;
import com.oop.payday.model.set.CashInEvaluator;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * 게임 상태 머신. 라운드 4단계(꾀부리기→분배→환금→종료)를 순서대로 진행하고
 * 라운드가 끝나면 분할/선택 역할을 교대한다(규칙서 §5~6).
 *
 * <p>{@link #play()} 는 사람 플레이어의 의사결정에서 블록하므로,
 * 호출자(UI)는 별도 스레드에서 실행해야 한다. 상태 변화는 {@link GameListener} 로 알린다.
 */
public final class Game {

    private final GameConfig config;
    private final Deck deck;
    private final GameListener listener;
    private final Random random = new Random();
    private final List<HelperCard> usedHelpers = new ArrayList<>();
    private final Map<Team, Integer> cashCountsThisRound = new HashMap<>();
    private final Set<Team> cashBlockedThisRound = new HashSet<>();

    private Team splitTeam;
    private Team chooseTeam;
    private int round;
    private Team instantWinner;

    private SplitDecision currentSplit;

    public Game(GameConfig config, Team firstSplit, Team firstChoose, GameListener listener) {
        this.config = config;
        this.deck = new Deck();
        this.listener = listener;
        this.splitTeam = firstSplit;
        this.chooseTeam = firstChoose;
        setupOfficers();
        configureHoldLimits(firstSplit);
        configureHoldLimits(firstChoose);
    }

    /** 게임을 끝까지 진행한다(승자가 나올 때까지). 블로킹 호출. */
    public void play() {
        setupHelpers();
        announceSetup();
        listener.onMessage("게임 시작! 목표: " + config.winningCoins() + "코인");
        round = 0;
        while (true) {
            round++;
            listener.onMessage("─── 라운드 " + round + " (분할: " + splitTeam.name() + ") ───");

            schemePhase();
            distributePhase();
            cashInPhase();
            Team winner = endPhase();

            listener.onRoundEnd(round);
            if (winner != null) {
                listener.onMessage("게임 종료 — 승리: " + winner.name());
                listener.onGameOver(winner);
                return;
            }
            swapRoles();
        }
    }

    // --- 6-1 꾀부리기 ---

    private void schemePhase() {
        listener.onPhaseChanged(Phase.SCHEME, round, splitTeam);
        Player splitter = splitTeam.leader();
        List<Card> hand = deck.draw(5);
        listener.onHandDealt(splitter, hand);

        SplitDecision decision = splitter.decideSplit(hand);
        if (!decision.isValid()) {
            throw new IllegalStateException("잘못된 분할 결정: " + splitTeam.name());
        }
        this.currentSplit = decision;
    }

    // --- 6-2 분배 ---

    private void distributePhase() {
        listener.onPhaseChanged(Phase.DISTRIBUTE, round, splitTeam);

        List<Card> bundleA = currentSplit.bundleA();
        List<Card> bundleB = currentSplit.bundleB();
        Card faceDown = currentSplit.faceDownCard();

        BundleView viewA = toBundleView(bundleA, faceDown);
        BundleView viewB = toBundleView(bundleB, faceDown);
        listener.onChoiceReady(new GameListener.BundlePair(
                viewA.visibleCards(), viewA.hasFaceDown(),
                viewB.visibleCards(), viewB.hasFaceDown()));

        Player chooser = chooseTeam.leader();
        int index = chooser.decideChoice(new ChoiceView(List.of(viewA, viewB)));
        if (index != 0 && index != 1) {
            throw new IllegalStateException("잘못된 선택 인덱스: " + index);
        }

        List<Card> chosen = index == 0 ? bundleA : bundleB;
        List<Card> other = index == 0 ? bundleB : bundleA;

        // 1v1: 각 팀 대표가 가져간 카드를 모두 보관. (다인 팀 내 분배는 추후)
        listener.onDistributed(index, chooseTeam, chosen, splitTeam, other);

        chooseTeam.leader().receiveAll(chosen);
        splitTeam.leader().receiveAll(other);

        // 가져간 카드 중 슬쩍하기가 있으면 즉시 처리(규칙서 §6-2).
        handleSteal(chooseTeam.leader(), chosen);
        handleSteal(splitTeam.leader(), other);
    }

    private void handleSteal(Player player, List<Card> takenCards) {
        for (Card card : takenCards) {
            if (card instanceof StealCard steal) {
                player.remove(steal);
                deck.absorbDiscardWith(steal);
                Card drawn = deck.draw();
                if (drawn != null) {
                    player.receive(drawn);
                }
                listener.onMessage(player.name() + " 슬쩍하기 발동 — 카드 더미에서 1장 획득");
            }
        }
    }

    private BundleView toBundleView(List<Card> bundle, Card faceDown) {
        List<Card> visible = new ArrayList<>(bundle);
        boolean hasFaceDown = visible.remove(faceDown);
        return new BundleView(visible, hasFaceDown);
    }

    // --- 6-3 환금 (모든 플레이어) ---

    private void cashInPhase() {
        listener.onPhaseChanged(Phase.CASH_IN, round, splitTeam);
        cashCountsThisRound.clear();
        cashBlockedThisRound.clear();
        for (Team team : List.of(splitTeam, chooseTeam)) {
            for (Player player : team.members()) {
                processCashIn(player, team);
                if (instantWinner != null) {
                    return;
                }
            }
        }
        applyEndOfCashLeaderEffects();
    }

    private void processCashIn(Player player, Team team) {
        CashInContext context = new CashInContext(
                player.holdings(), player.helpers(), usedHelpers, deck.discardView(), team.coins(), player.holdLimit());
        List<CashInAction> actions = player.decideCashIn(context);
        TreasureSet lastCashedSet = null;
        for (CashInAction action : actions) {
            switch (action) {
                case CashInAction.Cash cash -> {
                    TreasureSet set = applyCash(player, team, cash.cards());
                    if (set != null) {
                        lastCashedSet = set;
                    }
                }
                case CashInAction.Discard discard -> applyDiscard(player, team, discard.card());
                case CashInAction.UseHelper use -> applyHelper(player, team, use.helper(), lastCashedSet);
            }
            if (instantWinner != null) {
                return;
            }
        }
    }

    private TreasureSet applyCash(Player player, Team team, List<Card> cards) {
        if (cashBlockedThisRound.contains(team)) {
            listener.onMessage(team.name() + ": 이번 라운드 환금 금지 상태라 환금이 무시됨");
            return null;
        }
        Optional<CashInEvaluator.Result> evaluation = CashInEvaluator.evaluate(cards);
        if (evaluation.isEmpty() || !player.holdings().containsAll(cards)) {
            listener.onMessage(player.name() + ": 무효한 환금 무시됨");
            return null;
        }
        TreasureSet set = evaluation.get().set();
        int beforeCoins = team.coins();
        player.removeAll(evaluation.get().selectedCards());
        deck.discard(evaluation.get().selectedCards());
        team.addCoins(set.coin());
        cashCountsThisRound.merge(team, 1, Integer::sum);
        listener.onCashIn(player, set);
        if (evaluation.get().hasFreeCursedCards()) {
            listener.onMessage(player.name() + " 저주받은 그림 무료 처분: " + evaluation.get().freeCursedCards());
        }
        listener.onCoinsChanged(team, team.coins() - beforeCoins);
        applyAfterCashLeaderEffect(team, set);
        return set;
    }

    private void applyDiscard(Player player, Team team, Card card) {
        if (!player.holdings().contains(card)) {
            return;
        }
        int beforeCoins = team.coins();
        if (card instanceof CursedCard) {
            // 저주받은 그림은 자발적 처분 시 2코인을 지불한다(규칙서 §3-1).
            team.spendCoins(2);
        }
        player.remove(card);
        deck.discard(card);
        listener.onDiscard(player, card);
        int delta = team.coins() - beforeCoins;
        if (delta != 0) {
            listener.onCoinsChanged(team, delta);
        }
    }

    private void applyHelper(Player player, Team team, HelperCard helper, TreasureSet lastCashedSet) {
        if (!player.ownsHelper(helper) || helper.isUsed()) {
            listener.onMessage(player.name() + ": 사용할 수 없는 도우미");
            return;
        }
        int beforeCoins = team.coins();
        HelperUseContext context = new HelperUseContext(
                player, team, opponentOf(team), deck, lastCashedSet, usedHelpers);
        if (!helper.canUse(context)) {
            listener.onMessage(player.name() + ": " + helper.displayName() + " 조건 불충족");
            return;
        }
        helper.use(context);
        usedHelpers.add(helper);
        if (context.cashBlocked()) {
            cashBlockedThisRound.add(team);
        }
        if (context.holdLimitSuspended()) {
            player.suspendHoldLimit();
        }
        if (context.instantWinner() != null) {
            instantWinner = context.instantWinner();
        }
        String message = context.message() == null ? helper.displayName() + " 사용" : context.message();
        listener.onHelperUsed(player, helper, message);
        listener.onMessage(player.name() + " 도움 요청: " + message);
        int delta = team.coins() - beforeCoins;
        if (delta != 0) {
            listener.onCoinsChanged(team, delta);
        }
    }

    // --- 6-4 종료 ---

    private Team endPhase() {
        listener.onPhaseChanged(Phase.END, round, splitTeam);
        Team winner = checkWinner();
        if (winner != null) {
            return winner;
        }
        enforceHoldLimit(splitTeam);
        enforceHoldLimit(chooseTeam);
        return null;
    }

    /**
     * 승리 확인: 승리 코인 이상인 팀이 하나라도 있으면 그 순간 코인이 더 많은 팀이 승리.
     * 도달한 팀이 없거나 양 팀이 도달했으면서 동점이면 {@code null}(게임 계속).
     */
    private Team checkWinner() {
        if (instantWinner != null) {
            return instantWinner;
        }
        int win = config.winningCoins();
        boolean anyReached = splitTeam.coins() >= win || chooseTeam.coins() >= win;
        if (!anyReached) {
            return null;
        }
        if (splitTeam.coins() == chooseTeam.coins()) {
            return null;
        }
        return splitTeam.coins() > chooseTeam.coins() ? splitTeam : chooseTeam;
    }

    private void enforceHoldLimit(Team team) {
        for (Player player : team.members()) {
            if (player.isHoldLimitSuspended()) {
                player.clearHoldLimitSuspension();
                listener.onMessage(player.name() + " 이번 라운드 보유 한도 무시");
                continue;
            }
            if (player.holdingCount() <= player.holdLimit()) {
                continue;
            }
            listener.onMessage(player.name() + " 보유 한도 초과: "
                    + player.holdingCount() + "/" + player.holdLimit());
        }
    }

    // --- 공통 ---

    private void swapRoles() {
        Team tmp = splitTeam;
        splitTeam = chooseTeam;
        chooseTeam = tmp;
    }

    private void configureHoldLimits(Team team) {
        List<Player> members = team.members();
        if (members.size() == 1) {
            members.get(0).setHoldLimit(5 + holdLimitBonus(members.get(0), team));
        } else {
            members.get(0).setHoldLimit(3 + holdLimitBonus(members.get(0), team)); // 리더
            for (int i = 1; i < members.size(); i++) {
                members.get(i).setHoldLimit(4 + holdLimitBonus(members.get(i), team)); // 멤버
            }
        }
    }

    private int holdLimitBonus(Player player, Team team) {
        if (!player.isLeader() || player.officer() == null) {
            return 0;
        }
        return player.officer().holdLimitBonus(new LeaderContext(
                player, team, opponentOf(team), null, 0, 0, config.leaderEffectsEnabled()));
    }

    private void setupOfficers() {
        for (Team team : List.of(splitTeam, chooseTeam)) {
            for (Player player : team.members()) {
                player.setLeader(false);
            }
            team.leader().setLeader(true);
        }

        List<Player> players = allPlayers();
        List<OfficerTile> pool = new ArrayList<>(EnumSet.allOf(OfficerTile.class));
        pool.remove(OfficerTile.FLANKY);
        Collections.shuffle(pool, random);

        List<OfficerTile> tiles = new ArrayList<>();
        tiles.add(OfficerTile.FLANKY);
        tiles.addAll(pool.subList(0, Math.max(0, players.size() - 1)));
        Collections.shuffle(tiles, random);

        for (int i = 0; i < players.size(); i++) {
            players.get(i).setOfficer(tiles.get(i));
        }
        if (teamHasOfficer(chooseTeam, OfficerTile.FLANKY)) {
            swapRoles();
        }
    }

    private void setupHelpers() {
        List<HelperCard> helperDeck = HelperCards.shuffledDeck(random);
        for (Team team : List.of(splitTeam, chooseTeam)) {
            Player player = team.leader();
            List<HelperCard> options = drawHelpers(helperDeck, 3);
            List<HelperCard> selected = player.decideHelpers(options, 2);
            if (!isValidHelperSelection(options, selected, 2)) {
                selected = options.subList(0, Math.min(2, options.size()));
            }
            player.receiveHelpers(selected);
            listener.onPlayerSetup(player);
        }
    }

    private List<HelperCard> drawHelpers(List<HelperCard> helperDeck, int count) {
        List<HelperCard> result = new ArrayList<>();
        for (int i = 0; i < count && !helperDeck.isEmpty(); i++) {
            result.add(helperDeck.remove(0));
        }
        return result;
    }

    private boolean isValidHelperSelection(List<HelperCard> options, List<HelperCard> selected, int count) {
        return selected != null && selected.size() == count
                && new HashSet<>(selected).size() == selected.size()
                && options.containsAll(selected);
    }

    private void announceSetup() {
        for (Team team : List.of(splitTeam, chooseTeam)) {
            for (Player player : team.members()) {
                listener.onPlayerSetup(player);
                String leaderMark = player.isLeader() ? " 리더" : "";
                String officer = player.officer() == null ? "간부 없음" : player.officer().korean();
                listener.onMessage(player.name() + leaderMark + " — 간부: " + officer
                        + ", 도우미: " + player.helpers());
            }
        }
    }

    private List<Player> allPlayers() {
        List<Player> players = new ArrayList<>();
        players.addAll(splitTeam.members());
        players.addAll(chooseTeam.members());
        return players;
    }

    private boolean teamHasOfficer(Team team, OfficerTile officer) {
        return team.members().stream().anyMatch(player -> player.officer() == officer);
    }

    private Team opponentOf(Team team) {
        return team == splitTeam ? chooseTeam : splitTeam;
    }

    private Player leaderOf(Team team) {
        return team.members().stream().filter(Player::isLeader).findFirst().orElse(team.leader());
    }

    private void applyAfterCashLeaderEffect(Team team, TreasureSet set) {
        Player leader = leaderOf(team);
        if (leader.officer() == null) {
            return;
        }
        int bonus = leader.officer().bonusAfterCash(new LeaderContext(
                leader, team, opponentOf(team), set,
                cashCountsThisRound.getOrDefault(team, 0),
                countCurses(opponentOf(team)), config.leaderEffectsEnabled()));
        if (bonus > 0) {
            int beforeCoins = team.coins();
            team.addCoins(bonus);
            listener.onMessage(leader.officer().korean() + " 리더 효과 — " + bonus + "코인");
            listener.onCoinsChanged(team, team.coins() - beforeCoins);
        }
    }

    private void applyEndOfCashLeaderEffects() {
        for (Team team : List.of(splitTeam, chooseTeam)) {
            Player leader = leaderOf(team);
            if (leader.officer() == null) {
                continue;
            }
            int bonus = leader.officer().bonusAfterCashPhase(new LeaderContext(
                    leader, team, opponentOf(team), null,
                    cashCountsThisRound.getOrDefault(team, 0),
                    countCurses(opponentOf(team)), config.leaderEffectsEnabled()));
            if (bonus > 0) {
                int beforeCoins = team.coins();
                team.addCoins(bonus);
                listener.onMessage(leader.officer().korean() + " 리더 효과 — " + bonus + "코인");
                listener.onCoinsChanged(team, team.coins() - beforeCoins);
            }
        }
    }

    private int countCurses(Team team) {
        int count = 0;
        for (Player player : team.members()) {
            for (Card card : player.holdings()) {
                if (card instanceof CursedCard) {
                    count++;
                }
            }
        }
        return count;
    }

    public int round() {
        return round;
    }

    public Team splitTeam() {
        return splitTeam;
    }

    public Team chooseTeam() {
        return chooseTeam;
    }
}
