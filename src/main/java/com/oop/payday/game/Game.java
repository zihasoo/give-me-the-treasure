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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

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
import com.oop.payday.model.helper.HelperRules;
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

    // 환금 이벤트 루프(액터 모델): 사람·봇·(미래)네트워크가 행동을 큐에 넣고, 게임 스레드가 하나씩 처리한다.
    private final BlockingQueue<Submission> cashInbox = new LinkedBlockingQueue<>();
    private final Map<Player, TreasureSet> lastCashedSet = new HashMap<>();

    /** 환금 큐 제출 한 건. {@code action == null} 이면 "턴 종료(pass)". */
    private record Submission(Player who, CashInAction action) {}

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
        listener.onGameSetup(allPlayers());
        listener.awaitAnimations();
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
        listener.awaitAnimations();
        Player splitter = splitTeam.leader();
        List<Card> hand = deck.draw(5);
        listener.onHandDealt(splitter, hand);

        listener.onRequestSplit(splitter, hand);
        SplitDecision decision = splitter.decideSplit(hand);
        if (!decision.isValid()) {
            throw new IllegalStateException("잘못된 분할 결정: " + splitTeam.name());
        }
        this.currentSplit = decision;
    }

    // --- 6-2 분배 ---

    private void distributePhase() {
        listener.onPhaseChanged(Phase.DISTRIBUTE, round, splitTeam);
        listener.awaitAnimations();

        List<Card> bundleA = currentSplit.bundleA();
        List<Card> bundleB = currentSplit.bundleB();
        Card faceDown = currentSplit.faceDownCard();

        BundleView viewA = toBundleView(bundleA, faceDown);
        BundleView viewB = toBundleView(bundleB, faceDown);
        listener.onChoiceReady(new GameListener.BundlePair(
                viewA.visibleCards(), viewA.hasFaceDown(),
                viewB.visibleCards(), viewB.hasFaceDown()));

        Player chooser = chooseTeam.leader();
        ChoiceView choiceView = new ChoiceView(List.of(viewA, viewB));
        listener.onRequestChoice(chooser, choiceView);
        int index = chooser.decideChoice(choiceView);
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
                processSteal(player, steal);
            }
        }
    }

    /** 슬쩍하기 1회 처리. 뽑은 카드도 슬쩍하기면 재귀 호출. */
    private void processSteal(Player player, StealCard steal) {
        player.remove(steal);
        deck.absorbDiscardWith(steal);
        Card drawn = deck.draw();
        if (drawn != null) {
            player.receive(drawn);
        }
        listener.onStealActivated(player, drawn);
        listener.awaitAnimations();
        if (drawn instanceof StealCard nextSteal) {
            processSteal(player, nextSteal);
        }
    }

    private BundleView toBundleView(List<Card> bundle, Card faceDown) {
        List<Card> visible = new ArrayList<>(bundle);
        boolean hasFaceDown = visible.remove(faceDown);
        return new BundleView(visible, hasFaceDown);
    }

    // --- 6-3 환금 (모든 플레이어) ---

    /**
     * 환금 단계를 액터 모델 이벤트 루프로 진행한다(규칙서 §6-3, "모든 플레이어 동시 진행").
     *
     * <p>사람·봇·(미래)네트워크는 각자 원하는 타이밍에 {@link #cashInbox} 로 행동을 제출하고,
     * 게임 스레드(여기)가 <b>하나씩 꺼내 적용 → 연출이 끝날 때까지 대기(lockstep) → 최신 스냅샷을
     * 모두에게 방송</b>한다. 상태는 이 스레드만 바꾸므로 락이 필요 없다. 사람이 봇의 도우미 발동을 보고
     * 크록으로 따라가는 식의 교차 플레이가 자연히 나온다. 모든 플레이어가 "턴 종료"하면 단계가 끝난다.
     */
    private void cashInPhase() {
        listener.onPhaseChanged(Phase.CASH_IN, round, splitTeam);
        listener.awaitAnimations();
        cashCountsThisRound.clear();
        cashBlockedThisRound.clear();
        cashInbox.clear();
        lastCashedSet.clear();

        List<Player> players = new ArrayList<>();
        Map<Player, Team> playerTeam = new HashMap<>();
        for (Team team : List.of(splitTeam, chooseTeam)) {
            for (Player p : team.members()) {
                players.add(p);
                playerTeam.put(p, team);
            }
        }

        Set<Player> passed = new HashSet<>();

        // 봇은 가상 스레드 액터로: 초기 스냅샷으로 계획을 한 번 만들어 행동을 순서대로 제출하고 마지막에 패스.
        // 스냅샷은 게임 스레드(여기)에서 생성해 ConcurrentModificationException 을 방지한다.
        for (Player p : players) {
            if (p.isBot()) {
                CashInContext snapshot = snapshotFor(p, playerTeam.get(p));
                Thread.ofVirtual().start(() -> runBotActor(p, snapshot));
            }
        }
        broadcastCashTurn(players, passed);        // 사람들에게 초기 패널을 띄운다.

        while (passed.size() < players.size() && instantWinner == null) {
            Submission sub = takeSubmission();
            if (sub == null) {
                continue;
            }
            Player who = sub.who();
            Team team = playerTeam.get(who);
            if (team == null || passed.contains(who)) {
                continue;
            }
            if (sub.action() == null) {              // 턴 종료
                passed.add(who);
                listener.onCashDone(who);
                continue;
            }
            applyCashSingle(who, team, sub.action());
            if (instantWinner != null) {
                return;
            }
            if (cashBlockedThisRound.contains(team)) { // 고물상: 환금 불가 팀 자동 종료.
                passed.add(who);
                listener.onCashDone(who);
            }
            listener.awaitAnimations();              // lockstep: 연출이 끝난 뒤 다음 행동을 처리.
            broadcastCashTurn(players, passed);      // 모든 사람의 패널을 최신 상태로 갱신.
        }
        applyEndOfCashLeaderEffects();
    }

    /** 봇 가상 스레드 액터: 초기 스냅샷으로 계획을 세우고 행동을 하나씩 제출한 뒤 패스를 보낸다. */
    private void runBotActor(Player bot, CashInContext snapshot) {
        List<CashInAction> plan = bot.decideCashIn(snapshot);
        for (CashInAction action : plan) {
            pause(bot.nextCashPaceMillis());
            cashInbox.offer(new Submission(bot, action));
        }
        pause(bot.nextCashPaceMillis());
        cashInbox.offer(new Submission(bot, null)); // 패스
    }

    private static void pause(int millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 환금 인박스에서 다음 제출을 블로킹 대기한다. 인터럽트 시 {@code null}. */
    private Submission takeSubmission() {
        try {
            return cashInbox.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 아직 안 끝낸 사람들에게 최신 스냅샷을 방송해 패널을 (재)렌더링시킨다. */
    private void broadcastCashTurn(List<Player> players, Set<Player> passed) {
        for (Player p : players) {
            if (!p.isBot() && !passed.contains(p)) {
                listener.onCashTurn(p, snapshotFor(p, teamOf(p)));
            }
        }
    }

    private Team teamOf(Player player) {
        return splitTeam.members().contains(player) ? splitTeam : chooseTeam;
    }

    /** 큐에서 꺼낸 행동 하나를 적용한다. */
    private void applyCashSingle(Player player, Team team, CashInAction action) {
        switch (action) {
            case CashInAction.Cash cash -> {
                TreasureSet set = applyCash(player, team, cash.cards());
                if (set != null) {
                    lastCashedSet.put(player, set);
                }
            }
            case CashInAction.CashWithHelpers cash -> {
                TreasureSet set = applyCash(player, team, cash.cards());
                if (set != null) {
                    lastCashedSet.put(player, set);
                    for (HelperCard helper : cash.helpers()) {
                        if (!HelperRules.isCashReaction(helper.kind())) {
                            listener.onMessage(player.name() + ": " + helper.displayName()
                                    + "은(는) 환금 보너스로 사용할 수 없음");
                            continue;
                        }
                        applyHelper(player, team, helper, set, null, List.of());
                        if (instantWinner != null) {
                            return;
                        }
                    }
                }
            }
            case CashInAction.Discard discard -> applyDiscard(player, team, discard.card());
            case CashInAction.UseHelper use ->
                applyHelper(player, team, use.helper(), lastCashedSet.get(player), use.copyTarget(), use.selectedCards());
        }
    }

    /** 사람 UI(FX 스레드)가 행동을 제출한다. {@code action == null} 이면 턴 종료. */
    public void submitCash(Player who, CashInAction action) {
        cashInbox.offer(new Submission(who, action));
    }

    /** 사람 UI(FX 스레드)가 턴 종료를 제출한다. */
    public void passCash(Player who) {
        cashInbox.offer(new Submission(who, null));
    }

    private CashInContext snapshotFor(Player player, Team team) {
        int limit = player.isHoldLimitSuspended() ? Integer.MAX_VALUE : player.holdLimit();
        return new CashInContext(player.holdings(), player.helpers(), usedHelpers,
                deck.discardView(), team.coins(), limit);
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

    /** 도우미 효과를 적용하고, 효과로 손패가 늘었는지(드로우 발생 여부)를 돌려준다. */
    private boolean applyHelper(Player player, Team team, HelperCard helper, TreasureSet lastCashedSet,
            HelperCard copyTarget, List<Card> selectedCards) {
        if (!player.ownsHelper(helper) || helper.isUsed()) {
            listener.onMessage(player.name() + ": 사용할 수 없는 도우미");
            return false;
        }
        int beforeCoins = team.coins();
        Set<Card> holdingsBefore = new HashSet<>(player.holdings());
        HelperUseContext context = new HelperUseContext(
                player, team, opponentOf(team), deck, lastCashedSet, usedHelpers, copyTarget, selectedCards);
        if (!helper.canUse(context)) {
            listener.onMessage(player.name() + ": " + helper.displayName() + " "
                    + HelperRules.availability(helper, context).reason());
            return false;
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
        // 효과로 손패에 더해지거나(드로우) 빠진(처분) 카드를 가려내 연출용 정보로 넘긴다.
        Set<Card> holdingsAfter = new HashSet<>(player.holdings());
        List<Card> drawn = player.holdings().stream()
                .filter(c -> !holdingsBefore.contains(c))
                .toList();
        List<Card> discarded = holdingsBefore.stream()
                .filter(c -> !holdingsAfter.contains(c))
                .toList();
        String message = context.message() == null ? helper.displayName() + " 사용" : context.message();
        listener.onHelperUsed(player, helper, message, drawn, discarded);
        listener.onMessage(player.name() + " 도움 요청: " + message);
        int delta = team.coins() - beforeCoins;
        if (delta != 0) {
            listener.onCoinsChanged(team, delta);
        }
        // 도우미 효과로 새로 드로우한 카드 중 슬쩍하기가 있으면 즉시 처리.
        handleSteal(player, drawn);
        return !drawn.isEmpty();
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
        Player splitLeader = splitTeam.leader();
        Player chooseLeader = chooseTeam.leader();

        // 후보 드로우 (게임 스레드, 순차 — 덱 순서 결정론적)
        List<HelperCard> optionsSplit = drawHelpers(helperDeck, 3);
        List<HelperCard> optionsChoose = drawHelpers(helperDeck, 3);

        // 선택 (동시, 가상 스레드)
        @SuppressWarnings("unchecked")
        List<HelperCard>[] results = new List[2];
        runConcurrently(List.of(
                () -> {
                    listener.onRequestHelpers(splitLeader, optionsSplit, 2);
                    results[0] = splitLeader.decideHelpers(optionsSplit, 2);
                },
                () -> {
                    listener.onRequestHelpers(chooseLeader, optionsChoose, 2);
                    results[1] = chooseLeader.decideHelpers(optionsChoose, 2);
                }));

        // 검증·등록 (게임 스레드, 순차)
        applyHelperSelection(splitLeader, optionsSplit, results[0]);
        applyHelperSelection(chooseLeader, optionsChoose, results[1]);
    }

    private void applyHelperSelection(Player player, List<HelperCard> options, List<HelperCard> selected) {
        if (!isValidHelperSelection(options, selected, 2)) {
            selected = options.subList(0, Math.min(2, options.size()));
        }
        player.receiveHelpers(selected);
        listener.onPlayerSetup(player);
    }

    /** 태스크 목록을 가상 스레드로 동시 실행하고 전부 완료될 때까지 블록(join 배리어). */
    private void runConcurrently(List<Runnable> tasks) {
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = tasks.stream().map(exec::submit).toList();
            for (var f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException("동시 실행 중 오류", cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("동시 실행 중 인터럽트", e);
                }
            }
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
