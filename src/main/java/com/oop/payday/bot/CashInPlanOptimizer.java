package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperRules;
import com.oop.payday.model.set.CashInEvaluator;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;

/**
 * 봇 환금 계획 최적화기. 보유 카드에서 가능한 세트 후보를 모두 만든 뒤, 서로 겹치지
 * 않는 조합 중 총 기대 점수가 가장 높은 계획을 고른다.
 */
final class CashInPlanOptimizer {

    /** 보유 카드를 와일드로 채워 환금할 때 무는 기본 페널티(잠재 와일드 보존을 위해). */
    private static final int WILD_PENALTY = 35;
    /** 남은 카드 잠재력을 환금 점수에 섞을 때의 축소 비율(코인이 승패를 지배하도록). */
    private static final int POTENTIAL_DIVISOR = 4;

    private CashInPlanOptimizer() {
    }

    /** S1: 승리 임박을 보지 않는 기본 계획. */
    static List<CashInAction> plan(CashInContext context) {
        return planWith(context, false);
    }

    /**
     * S2: 자기 팀 코인 기준 승리 임박을 반영한다.
     *
     * @param winAware {@code true} 이고 팀이 승리 코인에 가까우면 와일드 보존 페널티와 미래 잠재력
     *                 가중치를 빼고 즉시 환금 코인을 최대화한다.
     */
    static List<CashInAction> plan(CashInContext context, boolean winAware) {
        return planWith(context, winAware && isImminent(context));
    }

    /**
     * S3: 자기 팀과 상대 팀 코인을 함께 본다. 어느 한쪽이라도 승리에 임박하면(목표의 70% 이상)
     * 게임이 곧 끝날 수 있으므로 보존·잠재력을 버리고 지금 모을 수 있는 코인을 최대화한다.
     */
    static List<CashInAction> plan(CashInContext context, int opponentCoins) {
        return planWith(context, isEndgame(context, opponentCoins));
    }

    private static List<CashInAction> planWith(CashInContext context, boolean winNow) {
        List<SetCandidate> candidates = enumerateCandidates(context.holdings(), context.helpers(), winNow);
        Plan best = findBestPlan(candidates, context.holdings(), winNow);
        List<CashInAction> actions = new ArrayList<>();
        List<Card> remaining = new ArrayList<>(context.holdings());
        Set<HelperCard> queuedHelpers = new HashSet<>();

        for (SetCandidate candidate : best.candidates()) {
            List<HelperCard> helpers = usefulConditionalHelpers(context.helpers(), queuedHelpers, candidate.set());
            actions.add(helpers.isEmpty()
                    ? new CashInAction.Cash(candidate.selectedCards())
                    : new CashInAction.CashWithHelpers(candidate.selectedCards(), helpers));
            queuedHelpers.addAll(helpers);
            remaining.removeAll(candidate.selectedCards());
        }

        addUsefulStandaloneHelper(actions, context, remaining, queuedHelpers, !best.candidates().isEmpty());
        if (!isTuskerQueued(actions)) {
            discardDownToLimit(actions, remaining, context.holdLimit());
        }
        return actions;
    }

    /** 팀이 승리 코인의 70% 이상에 도달했으면 승리 임박으로 본다. */
    private static boolean isImminent(CashInContext context) {
        return context.winningCoins() > 0
                && context.teamCoins() * 10 >= context.winningCoins() * 7;
    }

    /**
     * 종반 판정: 우리 팀 또는 상대 팀 중 하나라도 승리 코인의 70% 이상이면 게임이 곧 끝날 수 있다.
     * 가장 적게 남은 쪽(={@code min(myNeed, oppNeed)})이 목표의 30% 이하인지로 본다.
     */
    private static boolean isEndgame(CashInContext context, int opponentCoins) {
        int winning = context.winningCoins();
        if (winning <= 0) {
            return false;
        }
        int myNeed = winning - context.teamCoins();
        int oppNeed = winning - opponentCoins;
        int closest = Math.min(myNeed, oppNeed);
        return closest * 10 <= winning * 3;
    }

    private static List<SetCandidate> enumerateCandidates(List<Card> holdings, List<HelperCard> helpers,
            boolean winNow) {
        List<Card> usable = holdings.stream()
                .filter(card -> card.canFormSet() || card.isWild())
                .toList();
        List<SetCandidate> candidates = new ArrayList<>();
        for (int size = 2; size <= 5; size++) {
            collectCandidates(usable, holdings, helpers, size, 0, new ArrayList<>(), candidates, winNow);
        }
        candidates.sort(Comparator.comparingInt(SetCandidate::score).reversed());
        if (candidates.size() > 120) {
            return List.copyOf(candidates.subList(0, 120));
        }
        return candidates;
    }

    private static void collectCandidates(List<Card> usable, List<Card> holdings, List<HelperCard> helpers,
            int targetSize, int start, List<Card> picked, List<SetCandidate> result, boolean winNow) {
        if (picked.size() == targetSize) {
            CashInEvaluator.evaluate(picked)
                    .map(evaluation -> withMatchingCurses(evaluation.set(), holdings))
                    .ifPresent(setCards -> result.add(toCandidate(setCards, helpers, holdings, winNow)));
            return;
        }
        for (int i = start; i <= usable.size() - (targetSize - picked.size()); i++) {
            picked.add(usable.get(i));
            collectCandidates(usable, holdings, helpers, targetSize, i + 1, picked, result, winNow);
            picked.remove(picked.size() - 1);
        }
    }

    private static List<Card> withMatchingCurses(TreasureSet set, List<Card> holdings) {
        List<Card> selected = new ArrayList<>(set.cards());
        for (Card card : holdings) {
            if (card instanceof CursedCard && canCashWith(selected, card)) {
                selected.add(card);
            }
        }
        return selected;
    }

    private static SetCandidate toCandidate(List<Card> selectedCards, List<HelperCard> helpers, List<Card> holdings,
            boolean winNow) {
        var evaluation = CashInEvaluator.evaluate(selectedCards).orElseThrow();
        TreasureSet set = evaluation.set();
        int helperBonus = helpers.stream()
                .filter(helper -> !helper.isUsed())
                .mapToInt(helper -> reactionBonus(helper.kind(), set))
                .sum();
        int curseValue = evaluation.freeCursedCards().size() * 8;
        // preserveValue는 여기서 제거 — search()에서 한 번만 더한다(이중 계산 방지)
        // 승리 임박 시에는 와일드를 아끼지 않고 즉시 환금하므로 보존 페널티를 없앤다.
        int wildPenalty = winNow ? 0 : (int) selectedCards.stream().filter(Card::isWild).count() * WILD_PENALTY;
        int score = set.coin() * 100 + helperBonus * 100 + curseValue - wildPenalty;
        return new SetCandidate(set, selectedCards, score);
    }

    private static Plan findBestPlan(List<SetCandidate> candidates, List<Card> holdings, boolean winNow) {
        // "아무것도 환금 안 함" 기준점 = 0 (코인 환금은 항상 이득이므로 항상 양수여야 함)
        Plan best = new Plan(List.of(), 0);
        return search(candidates, 0, new HashSet<>(), new ArrayList<>(), best, holdings, winNow);
    }

    private static Plan search(List<SetCandidate> candidates, int index, Set<Card> used, List<SetCandidate> picked,
            Plan best, List<Card> holdings, boolean winNow) {
        if (!picked.isEmpty()) {
            // 잠재력은 tiebreaker 역할로 축소(/ 4): 코인이 승패를 결정하므로 coin*100이 지배.
            // 승리 임박 시에는 미래를 따지지 않고 지금 모을 수 있는 코인만 본다(잠재력 가중치 0).
            int potential = winNow ? 0 : remainingPotentialAfter(holdings, used.stream().toList()) / POTENTIAL_DIVISOR;
            int score = picked.stream().mapToInt(SetCandidate::score).sum() + potential;
            if (score > best.score()) {
                best = new Plan(List.copyOf(picked), score);
            }
        }

        for (int i = index; i < candidates.size(); i++) {
            SetCandidate candidate = candidates.get(i);
            if (overlaps(used, candidate.selectedCards())) {
                continue;
            }
            used.addAll(candidate.selectedCards());
            picked.add(candidate);
            best = search(candidates, i + 1, used, picked, best, holdings, winNow);
            picked.remove(picked.size() - 1);
            used.removeAll(candidate.selectedCards());
        }
        return best;
    }

    private static int remainingPotentialAfter(List<Card> holdings, List<Card> removed) {
        List<Card> remaining = new ArrayList<>(holdings);
        remaining.removeAll(removed);
        return BotCardEvaluator.potentialScore(remaining);
    }

    private static boolean overlaps(Set<Card> used, List<Card> cards) {
        for (Card card : cards) {
            if (used.contains(card)) {
                return true;
            }
        }
        return false;
    }

    private static boolean canCashWith(List<Card> selected, Card extra) {
        List<Card> candidate = new ArrayList<>(selected);
        candidate.add(extra);
        return CashInEvaluator.evaluate(candidate).isPresent();
    }

    private static List<HelperCard> usefulConditionalHelpers(List<HelperCard> helpers, Set<HelperCard> queuedHelpers,
            TreasureSet set) {
        List<HelperCard> result = new ArrayList<>();
        for (HelperCard helper : helpers) {
            if (helper.isUsed() || queuedHelpers.contains(helper)) {
                continue;
            }
            if (reactionBonus(helper.kind(), set) > 0) {
                result.add(helper);
            }
        }
        return result;
    }

    private static int reactionBonus(HelperKind kind, TreasureSet set) {
        return switch (kind) {
            case CUCKOO -> set.type() == SetType.RUN_SAME_COLOR && set.size() >= 3 ? 3 : 0;
            case LEO -> set.type() == SetType.SAME_NUMBER && set.size() >= 3 ? 3 : 0;
            case LUCKY -> set.type() == SetType.RUN_SAME_COLOR && set.size() == 5 ? 7 : 0;
            case ALPHA -> isAlphaSet(set) ? 50 : 0;
            default -> 0;
        };
    }

    private static boolean isAlphaSet(TreasureSet set) {
        return set.size() == 4
                && set.cards().stream().noneMatch(Card::isWild)
                && set.cards().stream().allMatch(card ->
                        card instanceof com.oop.payday.model.card.TreasureCard treasure
                                && treasure.number() == 1);
    }

    private static void addUsefulStandaloneHelper(List<CashInAction> actions, CashInContext context,
            List<Card> remaining, Set<HelperCard> queuedHelpers, boolean alreadyCashed) {
        HelperChoice best = null;
        for (HelperCard helper : context.helpers()) {
            if (helper.isUsed() || queuedHelpers.contains(helper) || HelperRules.isCashReaction(helper.kind())) {
                continue;
            }
            HelperChoice choice = standaloneChoice(helper, context, remaining, alreadyCashed);
            if (choice != null && (best == null || choice.score() > best.score())) {
                best = choice;
            }
        }
        if (best != null && best.score() > 0) {
            actions.add(new CashInAction.UseHelper(best.helper(), best.copyTarget(), best.selectedCards()));
            queuedHelpers.add(best.helper());
        }
    }

    private static HelperChoice standaloneChoice(HelperCard helper, CashInContext context, List<Card> remaining,
            boolean alreadyCashed) {
        return switch (helper.kind()) {
            case VIPER -> {
                long curses = remaining.stream().filter(CursedCard.class::isInstance).count();
                yield curses > 0 ? new HelperChoice(helper, null, List.of(), (int) curses * 35) : null;
            }
            case TUSKER -> {
                int excess = remaining.size() - context.holdLimit();
                yield excess > 0
                        ? new HelperChoice(helper, null, List.of(), 40 + excess * 15)
                        : null; // 한도 초과 아닐 때는 낭비 방지
            }
            case DOUG -> dougChoice(helper, remaining);
            case JUNK_DEALER -> !alreadyCashed && context.discardPile().stream().anyMatch(Card::isWild)
                    ? new HelperChoice(helper, null, List.of(), 18)
                    : null;
            case CROC_BROTHERS -> crocChoice(helper, context, remaining, alreadyCashed);
            default -> null;
        };
    }

    private static HelperChoice dougChoice(HelperCard helper, List<Card> remaining) {
        List<Card> selected = remaining.stream()
                .filter(card -> !(card instanceof CursedCard))
                .filter(card -> BotCardEvaluator.discardLoss(remaining, card) <= 8)
                .toList();
        if (selected.isEmpty()) {
            return null;
        }
        return new HelperChoice(helper, null, selected, selected.size() * 12);
    }

    private static HelperChoice crocChoice(HelperCard helper, CashInContext context, List<Card> remaining,
            boolean alreadyCashed) {
        HelperChoice best = null;
        for (HelperCard target : context.usedHelpers()) {
            if (target.kind() == HelperKind.CROC_BROTHERS) {
                continue;
            }
            HelperChoice copied = standaloneChoice(target, context, remaining, alreadyCashed);
            if (copied == null) {
                continue;
            }
            HelperChoice asCroc = new HelperChoice(helper, target, copied.selectedCards(), copied.score() - 4);
            if (best == null || asCroc.score() > best.score()) {
                best = asCroc;
            }
        }
        return best;
    }

    private static boolean isTuskerQueued(List<CashInAction> actions) {
        return actions.stream().anyMatch(action -> switch (action) {
            case CashInAction.UseHelper use -> use.helper().kind() == HelperKind.TUSKER;
            case CashInAction.CashWithHelpers cash -> cash.helpers().stream()
                    .anyMatch(helper -> helper.kind() == HelperKind.TUSKER);
            default -> false;
        });
    }

    private static void discardDownToLimit(List<CashInAction> actions, List<Card> remaining, int holdLimit) {
        if (holdLimit == Integer.MAX_VALUE) {
            return;
        }
        List<Card> projected = new ArrayList<>(remaining);
        if (hasViper(actions)) {
            projected.removeIf(CursedCard.class::isInstance);
        }
        while (projected.size() > holdLimit) {
            Card discard = BotCardEvaluator.chooseDiscard(projected);
            actions.add(new CashInAction.Discard(discard));
            projected.remove(discard);
            remaining.remove(discard);
        }
    }

    private static boolean hasViper(List<CashInAction> actions) {
        return actions.stream().anyMatch(action -> switch (action) {
            case CashInAction.UseHelper use -> use.helper().kind() == HelperKind.VIPER
                    || (use.copyTarget() != null && use.copyTarget().kind() == HelperKind.VIPER);
            case CashInAction.CashWithHelpers cash -> cash.helpers().stream()
                    .anyMatch(helper -> helper.kind() == HelperKind.VIPER);
            default -> false;
        });
    }

    private record SetCandidate(TreasureSet set, List<Card> selectedCards, int score) {
        private SetCandidate {
            selectedCards = List.copyOf(selectedCards);
        }
    }

    private record Plan(List<SetCandidate> candidates, int score) {
    }

    private record HelperChoice(HelperCard helper, HelperCard copyTarget, List<Card> selectedCards, int score) {
        private HelperChoice {
            selectedCards = List.copyOf(selectedCards);
        }
    }
}
