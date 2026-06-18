package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.card.WildCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperRules;
import com.oop.payday.model.set.CashInEvaluator;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;

/**
 * 봇 환금 계획 최적화기. 보유 카드에서 가능한 세트 후보를 모두 만든 뒤, 서로 겹치지
 * 않는 조합 중 총 기대 점수가 가장 높은 계획을 고른다.
 *
 * <p>종반이 아니면 성장 기대값이 큰 세트들을 한도가 허락하는 한 이번 턴 환금에서 제외해
 * (다중 세트 보류) 다음 라운드에 더 크게 키우고, 환금 단계 도우미(JUNK_DEALER 와일드 회수 등)는
 * 손패 빌드 잠재력에 비례해 활용한다.
 */
final class CashInPlanOptimizer {

    /** 보유 카드를 와일드로 채워 환금할 때 무는 기본 페널티(잠재 와일드 보존을 위해). */
    private static final int WILD_PENALTY = 35;
    /** 남은 카드 잠재력을 환금 점수에 섞을 때의 축소 비율(코인이 승패를 지배하도록). */
    private static final int POTENTIAL_DIVISOR = 4;

    private CashInPlanOptimizer() {
    }

    /**
     * 환금 계획을 만든다. 종반이 아니면 성장 기대값이 큰 세트들을 한도가 허락하는 한 보류해
     * 다음 라운드에 더 크게 키우고, 종반({@link #isEndgame})이면 보존·잠재력을 버리고 즉시 환금한다.
     * {@code opponentCoins} 는 상대 팀 코인으로, 상대 승리 임박 시 즉시 환금(종반 판단)에 쓴다.
     * {@code tuning} 으로 세대별 보류·도우미·저주 정책을 갈아끼운다.
     */
    static List<CashInAction> plan(CashInContext context, int opponentCoins, Tuning tuning) {
        boolean winNow = isEndgame(context, opponentCoins);
        boolean overLimit = context.holdings().size() > context.holdLimit();
        List<SetCandidate> candidates = enumerateCandidates(
                context.holdings(), context.helpers(), winNow, overLimit, tuning);
        Plan best = findBestPlan(candidates, context.holdings(), winNow);

        Set<SetCandidate> held = winNow ? identitySet() : pickSetsToHold(best, context, tuning);

        List<CashInAction> actions = new ArrayList<>();
        List<Card> remaining = new ArrayList<>(context.holdings());
        Set<HelperCard> queuedHelpers = new HashSet<>();

        for (SetCandidate candidate : best.candidates()) {
            if (held.contains(candidate)) continue;
            List<HelperCard> helpers = usefulConditionalHelpers(context.helpers(), queuedHelpers, candidate.set());
            actions.add(helpers.isEmpty()
                    ? new CashInAction.Cash(candidate.selectedCards())
                    : new CashInAction.CashWithHelpers(candidate.selectedCards(), helpers));
            queuedHelpers.addAll(helpers);
            remaining.removeAll(candidate.selectedCards());
        }

        boolean anyCashed = best.candidates().size() > held.size();
        addUsefulStandaloneHelper(actions, context, remaining, queuedHelpers, anyCashed, tuning);
        if (!isTuskerQueued(actions)) {
            int projectedCoins = projectedTeamCoins(context.teamCoins(), actions);
            discardDownToLimit(actions, remaining, context.holdLimit(), junkDealerDrawCount(actions), projectedCoins);
        }
        return actions;
    }

    /**
     * 환금 계획 튜닝 — 세대별로 다른 보류/도우미/저주 정책을 갈아끼운다.
     *
     * @param holdGrowthThreshold   성장 기대값이 이 값 이상인 세트만 보류 대상으로 본다.
     * @param flushGrowthMultiplier 같은색 연속 세트의 성장 기대값에 곱하는 가중(천장이 가장 높아 키울 값어치 큼).
     * @param freeCurseDisposalReward 세트에 끼워 저주를 무료 처분할 때 세트 후보 점수에 더하는 저주 1장당 보너스.
     * @param freeCurseRewardOnlyOverLimit true 면 보유 한도 초과일 때만 위 보너스를 적용한다.
     *                             <b>한도 여유가 있을 땐</b> 무료 처분은 이득이 아니라 덜 나쁜 손해이므로(저주가
     *                             보물이었다면 더 크게 냈을 것) 보너스 0 — 저주 털려고 세트를 일찍 내는 왜곡 방지.
     *                             <b>한도가 넘치면</b> 무료 처분이 2코인 강제 버림을 피하는 실질 이득이라 보너스를 켠다.
     * @param curseFreeHoldRoom    보류 가능 여부의 보유 한도 계산에서 처분 예정 저주를 제외해 보류 공간을 확보.
     * @param viperOnlyWhenOverLimit 척후 바이퍼를 보유 한도 초과(환금 뒤에도)일 때만 쓴다. 한도 여유가 있으면
     *                             저주는 매칭 세트로 무료 처분하거나 더 모았다가 쓰는 게 나아 1회용 바이퍼를 아낀다.
     * @param freeRoomForHold      true 면 보류 방이 부족할 때 세트 잠재력 없는 단독 잡카드(loose)를 버려 방을
     *                             확보한다. 1인팀 보유 한도가 5로 빡빡해, loose 를 다 쥐면 3장 세트를 보류할
     *                             공간이 영영 안 나 4·5장으로 못 키운다(사람 vs S7 로그 175208 = 봇 최대 3장).
     *                             키울 가치가 임계를 넘는 세트를 위해 잡카드를 비워 큰 세트 양성을 가능케 한다.
     */
    record Tuning(double holdGrowthThreshold, double flushGrowthMultiplier,
            int freeCurseDisposalReward, boolean freeCurseRewardOnlyOverLimit,
            boolean curseFreeHoldRoom, boolean viperOnlyWhenOverLimit, boolean freeRoomForHold) {

        /**
         * S7: 보류 임계 완화 + 같은색 연속 성장 가중 + 저주를 보유공간 부채로 취급 + 무료 처분은 한도 초과일 때만
         * 이득으로 봄(2코인 강제 버림 회피) + 바이퍼를 한도 초과 구제용으로만 + 보류 방이 부족하면 잡카드를 버려
         * 방을 확보. 사람 vs 봇 로그가 짚은 저주 운용·보류 약점을 교정한다. (TUSKER 로 저주를 보류해 다음
         * 무료 처분을 노리는 것은 합리적이라 기존 로직 그대로 둔다 — 저주를 그냥 버려도 2코인이 들기 때문.)
         */
        static final Tuning S7 = new Tuning(0.5, 2.0, 200, true, true, true, true);

        /**
         * S8: 환금 계획 수치는 S7 을 그대로 계승한다(환금 단계 안정성 유지). S8 의 향상은 분할/선택의
         * 공개 정보 메모리·상대 확률 모델·실현 코인 종반 평가에서 나오며, 환금 최적화 자체는 검증된
         * S7 경로를 공유한다. 추후 회귀를 보며 이 값을 독립적으로 조정한다.
         */
        static final Tuning S8 = new Tuning(0.5, 2.0, 200, true, true, true, true);
    }

    /**
     * 환금 계획과 함께 예상 코인/즉승/잔여 카드를 돌려주는 평가 API(로드맵 4.4). {@link #plan} 의 행동을
     * 그대로 쓰되, 반환된 행동을 되짚어 실현 코인을 근사한다(세트 코인 + 반응 도우미 보너스 − 저주 처분 비용,
     * ALPHA → 즉승). 봇은 이를 종반 판단·디버그 로그(후보 점수표)에 쓴다.
     */
    static CashInProjection project(CashInContext context, int opponentCoins, Tuning tuning) {
        List<CashInAction> actions = plan(context, opponentCoins, tuning);
        int coinsGained = 0;
        boolean instantWin = false;
        List<Card> remaining = new ArrayList<>(context.holdings());
        for (CashInAction action : actions) {
            switch (action) {
                case CashInAction.Cash cash -> {
                    coinsGained += setCoinOf(cash.cards());
                    remaining.removeAll(cash.cards());
                }
                case CashInAction.CashWithHelpers cash -> {
                    var evaluation = CashInEvaluator.evaluate(cash.cards());
                    if (evaluation.isPresent()) {
                        TreasureSet set = evaluation.get().set();
                        coinsGained += set.coin();
                        for (HelperCard helper : cash.helpers()) {
                            int bonus = reactionBonus(helper.kind(), set);
                            if (helper.kind() == HelperKind.ALPHA && bonus > 0) {
                                instantWin = true;
                            } else {
                                coinsGained += bonus;
                            }
                        }
                    }
                    remaining.removeAll(cash.cards());
                }
                case CashInAction.Discard discard -> {
                    if (discard.card() instanceof CursedCard) coinsGained -= 2;
                    remaining.remove(discard.card());
                }
                case CashInAction.UseHelper _ -> {
                    // VIPER 코인·드로우 등 도우미 단독 효과는 엔진 정산 소관이라 근사에서 제외한다.
                }
            }
        }
        return new CashInProjection(actions, coinsGained, instantWin, remaining);
    }

    private static int setCoinOf(List<Card> cards) {
        return CashInEvaluator.evaluate(cards).map(result -> result.set().coin()).orElse(0);
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
            boolean winNow, boolean overLimit, Tuning tuning) {
        List<Card> usable = holdings.stream()
                .filter(card -> card.canFormSet() || card.isWild())
                .toList();
        List<SetCandidate> candidates = new ArrayList<>();
        for (int size = 2; size <= 5; size++) {
            collectCandidates(usable, holdings, helpers, size, 0, new ArrayList<>(), candidates,
                    winNow, overLimit, tuning);
        }
        candidates.sort(Comparator.comparingInt(SetCandidate::score).reversed());
        if (candidates.size() > 120) {
            return List.copyOf(candidates.subList(0, 120));
        }
        return candidates;
    }

    private static void collectCandidates(List<Card> usable, List<Card> holdings, List<HelperCard> helpers,
            int targetSize, int start, List<Card> picked, List<SetCandidate> result,
            boolean winNow, boolean overLimit, Tuning tuning) {
        if (picked.size() == targetSize) {
            CashInEvaluator.evaluate(picked)
                    .map(evaluation -> withMatchingCurses(evaluation.set(), holdings))
                    .ifPresent(setCards -> result.add(toCandidate(setCards, helpers, winNow, overLimit, tuning)));
            return;
        }
        for (int i = start; i <= usable.size() - (targetSize - picked.size()); i++) {
            picked.add(usable.get(i));
            collectCandidates(usable, holdings, helpers, targetSize, i + 1, picked, result,
                    winNow, overLimit, tuning);
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

    private static SetCandidate toCandidate(List<Card> selectedCards, List<HelperCard> helpers, boolean winNow,
            boolean overLimit, Tuning tuning) {
        var evaluation = CashInEvaluator.evaluate(selectedCards).orElseThrow();
        TreasureSet set = evaluation.set();
        int helperBonus = helpers.stream()
                .filter(helper -> !helper.isUsed())
                .mapToInt(helper -> reactionBonus(helper.kind(), set))
                .sum();
        // 무료 처분 보너스: 한도 여유가 있을 땐 0(무료 처분은 이득이 아니라 덜 나쁜 손해 — 저주가 보물이었다면
        // 더 크게 냈을 것이라 일찍 환금하는 왜곡 방지). 한도가 넘치면 2코인 강제 버림을 피하는 실질 이득이라 켠다.
        int reward = tuning.freeCurseRewardOnlyOverLimit() && !overLimit ? 0 : tuning.freeCurseDisposalReward();
        int curseValue = evaluation.freeCursedCards().size() * reward;
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
            List<Card> remaining, Set<HelperCard> queuedHelpers, boolean alreadyCashed, Tuning tuning) {
        HelperChoice best = null;
        for (HelperCard helper : context.helpers()) {
            if (helper.isUsed() || queuedHelpers.contains(helper) || HelperRules.isCashReaction(helper.kind())) {
                continue;
            }
            HelperChoice choice = standaloneChoice(helper, context, remaining, alreadyCashed, tuning);
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
            boolean alreadyCashed, Tuning tuning) {
        return switch (helper.kind()) {
            case VIPER -> {
                long curses = remaining.stream().filter(CursedCard.class::isInstance).count();
                if (curses == 0) {
                    yield null;
                }
                // 한도 여유가 있으면 바이퍼를 쓰지 않는다(저주는 매칭 세트로 무료 처분하거나 더 모았다가 씀 —
                // 1회용 도우미 낭비 방지). 환금 뒤에도 한도가 넘칠 때만 구제책으로 쓴다. — 사용자 피드백.
                if (tuning.viperOnlyWhenOverLimit() && remaining.size() <= context.holdLimit()) {
                    yield null;
                }
                yield new HelperChoice(helper, null, List.of(), (int) curses * 35);
            }
            case TUSKER -> {
                // 저주를 그냥 버려도 2코인이 드므로, TUSKER 로 한도를 풀어 다음 라운드 무료 처분을 노리는 것은
                // 합리적이다(저주가 한도 초과의 원인이어도 마찬가지). 기존 로직 유지.
                int excess = remaining.size() - context.holdLimit();
                yield excess > 0
                        ? new HelperChoice(helper, null, List.of(), 40 + excess * 15)
                        : null; // 한도 초과 아닐 때는 낭비 방지
            }
            case DOUG -> dougChoice(helper, remaining);
            case JUNK_DEALER -> {
                if (alreadyCashed || context.discardPile().stream().noneMatch(Card::isWild)) {
                    yield null;
                }
                yield new HelperChoice(helper, null, List.of(), junkDealerScore(remaining));
            }
            case CROC_BROTHERS -> crocChoice(helper, context, remaining, alreadyCashed, tuning);
            default -> null;
        };
    }

    /**
     * 버림 더미에서 와일드를 회수하는 가치. 회수한 와일드가 현재 보관 카드와 만들 수 있는 빌드 잠재력
     * (특히 색 연속 완성)에 비례해 점수를 올린다 — JUNK_DEALER 는 이번 라운드 환금을 막으므로
     * "와일드로 대박 세트를 노릴 수 있을 때"만 적극적으로 쓰게 한다.
     */
    private static int junkDealerScore(List<Card> remaining) {
        List<Card> withWild = new ArrayList<>(remaining);
        withWild.add(new WildCard(-1)); // 평가 전용 프록시(보관/액션에 들어가지 않음)
        // 잠재력 증분에서 와일드 자체의 고정 보너스(38)를 빼 "구멍을 메우는 조합 가치"만 본다.
        int combinatorialGain = BotCardEvaluator.potentialScore(withWild)
                - BotCardEvaluator.potentialScore(remaining) - 38;
        return 24 + Math.max(0, Math.min(48, combinatorialGain));
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
            boolean alreadyCashed, Tuning tuning) {
        HelperChoice best = null;
        for (HelperCard target : context.usedHelpers()) {
            if (target.kind() == HelperKind.CROC_BROTHERS) {
                continue;
            }
            HelperChoice copied = standaloneChoice(target, context, remaining, alreadyCashed, tuning);
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
            case CashInAction.UseHelper use -> use.helper().kind() == HelperKind.TUSKER
                    || (use.copyTarget() != null && use.copyTarget().kind() == HelperKind.TUSKER);
            case CashInAction.CashWithHelpers cash -> cash.helpers().stream()
                    .anyMatch(helper -> helper.kind() == HelperKind.TUSKER);
            default -> false;
        });
    }

    private static void discardDownToLimit(List<CashInAction> actions, List<Card> remaining, int holdLimit,
            int extraDrawn, int availableCoins) {
        if (holdLimit == Integer.MAX_VALUE) {
            return;
        }
        List<Card> projected = new ArrayList<>(remaining);
        if (hasViper(actions)) {
            projected.removeIf(CursedCard.class::isInstance);
        }
        while (projected.size() + extraDrawn > holdLimit) {
            List<Card> eligible = availableCoins >= 2 ? projected
                    : projected.stream().filter(c -> !(c instanceof CursedCard)).toList();
            if (eligible.isEmpty()) break;
            Card discard = BotCardEvaluator.chooseDiscard(eligible);
            if (discard == null) break;
            if (discard instanceof CursedCard) availableCoins -= 2;
            actions.add(new CashInAction.Discard(discard));
            projected.remove(discard);
            remaining.remove(discard);
        }
    }

    private static int projectedTeamCoins(int teamCoins, List<CashInAction> actions) {
        int coins = teamCoins;
        for (CashInAction action : actions) {
            List<Card> cards = switch (action) {
                case CashInAction.Cash cash -> cash.cards();
                case CashInAction.CashWithHelpers cash -> cash.cards();
                default -> List.of();
            };
            if (!cards.isEmpty()) {
                coins += CashInEvaluator.evaluate(cards).map(r -> r.set().coin()).orElse(0);
            }
        }
        return coins;
    }

    private static int junkDealerDrawCount(List<CashInAction> actions) {
        int count = 0;
        for (CashInAction action : actions) {
            if (action instanceof CashInAction.UseHelper use) {
                if (use.helper().kind() == HelperKind.JUNK_DEALER) count++;
                else if (use.copyTarget() != null && use.copyTarget().kind() == HelperKind.JUNK_DEALER) count++;
            }
        }
        return count;
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

    // ─── 성장 기대값 기반 다중 세트 보류 ───────────────────────────────────────────

    /** 버림 더미에 있는 카드: 슬쩍하기로 회수 가능 → 낮은 예상 턴. */
    private static final double DISCARD_TURNS = 1.5;
    /** 상대 손패에 있는 카드: 상대가 버리거나 환금할 때까지 대기. */
    private static final double OPP_TURNS = 5.0;
    /** 라운드당 분배되는 평균 보물 카드 수(5장 중 보물 비율 근사). */
    private static final double DRAWS_PER_ROUND = 2.5;
    /** 미지 상태 보물 카드 전체 수 (4색 × 7숫자). */
    private static final int TOTAL_TREASURE =
            CardColor.values().length * (TreasureCard.MAX_NUMBER - TreasureCard.MIN_NUMBER + 1);
    /**
     * 보류 방 확보를 위해 버려도 되는 loose 카드의 잠재력 손실 상한({@link BotCardEvaluator#discardLoss}).
     * 이 이하면 세트 잠재력 없는 단독 잡카드로 본다(고립 보물 ≈ 4, 1번 단독 ≈ 9, 페어/런 재료는 10+ 라
     * 안 버려진다). 키울 세트를 위해 이런 잡카드만 비운다.
     */
    private static final int LOOSE_JUNK_CEILING = 8;

    /**
     * 베스트 플랜의 세트들 중 성장 기대값({@link #growthValue})이 큰 순으로, 보유 한도가 허락하는 한
     * <b>여러 개</b>를 이번 턴 환금에서 제외(보류)한다. 작은 세트를 자잘하게 내는 대신 키울 수 있는
     * 세트를 모아 크게 낸다. 승리 세트(코인 ≥ 필요량)와 성장 기대값이 임계 미만인 세트는 즉시 환금한다.
     */
    private static Set<SetCandidate> pickSetsToHold(Plan best, CashInContext context, Tuning tuning) {
        Set<SetCandidate> held = identitySet();
        if (best.candidates().isEmpty()) return held;
        int myNeed = context.winningCoins() - context.teamCoins();
        if (myNeed <= 0) return held;

        // 보류 후 다음 라운드로 넘어갈 비(非)세트 카드. 저주는 처분/버림 예정이므로(S7) 공간 계산에서 제외해
        // 저주가 보유 한도를 잡아먹어 키울 세트를 즉시 환금하게 되는 문제(사람 vs 봇 로그)를 푼다.
        List<Card> looseCards = new ArrayList<>(context.holdings());
        for (SetCandidate c : best.candidates()) looseCards.removeAll(c.selectedCards());
        List<Card> keepableLoose = tuning.curseFreeHoldRoom()
                ? looseCards.stream().filter(card -> !(card instanceof CursedCard)).toList()
                : looseCards;

        // 방이 부족할 때 버릴 후보: 잠재력 손실이 작은 순(잡카드 먼저). freeRoomForHold 면 이 순서로 비운다.
        List<Card> discardable = new ArrayList<>(keepableLoose);
        discardable.sort(Comparator.comparingInt(c -> BotCardEvaluator.discardLoss(keepableLoose, c)));

        List<SetCandidate> byGrowth = new ArrayList<>(best.candidates());
        byGrowth.sort(Comparator.comparingDouble((SetCandidate c) -> growthValue(c.set(),
                context.holdings(), context.discardPile(), context.opponentHoldings(), tuning)).reversed());

        int looseKept = keepableLoose.size();
        int heldCards = 0;
        for (SetCandidate candidate : byGrowth) {
            if (candidate.set().coin() >= myNeed) continue; // 승리 세트는 즉시 환금
            double value = growthValue(candidate.set(), context.holdings(),
                    context.discardPile(), context.opponentHoldings(), tuning);
            if (value < tuning.holdGrowthThreshold()) continue;
            int projected = looseKept + heldCards + candidate.selectedCards().size();
            // 방이 부족하면 세트 잠재력 없는 단독 잡카드(loose)를 버려 방을 만든다. 키울 가치(임계 통과)가 있는
            // 세트를 위해 잡카드를 비우는 트레이드오프 — 한도 5 에서 loose 를 다 쥐면 보류가 영영 불가하기 때문.
            while (tuning.freeRoomForHold() && projected > context.holdLimit() && !discardable.isEmpty()
                    && BotCardEvaluator.discardLoss(keepableLoose, discardable.get(0)) <= LOOSE_JUNK_CEILING) {
                discardable.remove(0);
                looseKept--;
                projected--;
            }
            if (projected > context.holdLimit()) continue; // 그래도 방을 못 만들면 보류 불가
            held.add(candidate);
            heldCards += candidate.selectedCards().size();
        }
        return held;
    }

    private static Set<SetCandidate> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * 세트의 성장 기대값: (목표 크기까지 코인 증분) / (누적 예상 획득 턴).
     * 가능한 목표 크기 중 가장 높은 값을 반환한다.
     * 첫 단계의 {@link #expectedStepTurns}을 전 단계에 동일하게 적용(근사).
     */
    private static double growthValue(TreasureSet set, List<Card> holdings,
            List<Card> discardPile, List<Card> opponentHoldings, Tuning tuning) {
        double stepTurns = expectedStepTurns(set, holdings, discardPile, opponentHoldings);
        if (stepTurns >= Double.MAX_VALUE / 2) return 0;

        int currentCoin = set.coin();
        double cumTurns = 0;
        double best = 0;
        for (int targetSize = set.size() + 1; ; targetSize++) {
            int targetCoin = set.type().coin(targetSize);
            if (targetCoin == SetType.INVALID) break;
            cumTurns += stepTurns;
            double value = (targetCoin - currentCoin) / cumTurns;
            if (value > best) best = value;
        }
        // 같은색 연속은 천장이 가장 높다(4장=9, 5장=15). 키울 값어치를 가중해 즉시 환금 대신 보류를 유도.
        if (set.type() == SetType.RUN_SAME_COLOR) best *= tuning.flushGrowthMultiplier();
        return best;
    }

    /**
     * 세트를 한 장 더 키우는 데 필요한 카드의 예상 획득 턴.
     * <ul>
     *   <li>버림 더미: {@link #DISCARD_TURNS} (슬쩍하기로 회수 가능)</li>
     *   <li>상대 손패: {@link #OPP_TURNS} (상대가 쓸 때까지 대기)</li>
     *   <li>미지(덱): 미지 보물 장수 / (미지 후보 수 × {@link #DRAWS_PER_ROUND})</li>
     * </ul>
     * 여러 후보가 있으면 가장 빠른 경로(min)를 반환한다.
     */
    private static double expectedStepTurns(TreasureSet set, List<Card> holdings,
            List<Card> discardPile, List<Card> opponentHoldings) {
        List<TargetSpec> targets = nextTargets(set);
        if (targets.isEmpty()) return Double.MAX_VALUE / 2;

        long knownTreasure = countTreasure(holdings) + countTreasure(discardPile)
                + countTreasure(opponentHoldings);
        long unknownTreasure = Math.max(1, TOTAL_TREASURE - knownTreasure);

        boolean anyInDiscard = false;
        boolean anyInOpp = false;
        long unknownAlts = 0;
        for (TargetSpec t : targets) {
            if (inTargetList(t, discardPile))       anyInDiscard = true;
            else if (inTargetList(t, opponentHoldings)) anyInOpp = true;
            else if (!inTargetList(t, holdings))    unknownAlts++;
        }

        double best = Double.MAX_VALUE / 2;
        if (anyInDiscard) best = Math.min(best, DISCARD_TURNS);
        if (anyInOpp)     best = Math.min(best, OPP_TURNS);
        if (unknownAlts > 0) best = Math.min(best, unknownTreasure / (unknownAlts * DRAWS_PER_ROUND));
        return best;
    }

    /** 세트를 한 장 키울 때 필요한 카드 목록(세트 타입별 일반화). */
    private static List<TargetSpec> nextTargets(TreasureSet set) {
        return switch (set.type()) {
            case SAME_NUMBER    -> nextTargetsSameNumber(set);
            case RUN            -> nextTargetsRun(set);
            case RUN_SAME_COLOR -> nextTargetsRunColor(set);
        };
    }

    private static List<TargetSpec> nextTargetsSameNumber(TreasureSet set) {
        int number = -1;
        Set<CardColor> usedColors = new HashSet<>();
        for (Card card : set.cards()) {
            if (card instanceof TreasureCard tc) {
                number = tc.number();
                usedColors.add(tc.color());
            }
        }
        if (number == -1) return List.of();
        List<TargetSpec> result = new ArrayList<>();
        for (CardColor c : CardColor.values()) {
            if (!usedColors.contains(c)) result.add(new TargetSpec(c, number));
        }
        return result;
    }

    private static List<TargetSpec> nextTargetsRun(TreasureSet set) {
        int[] range = runRange(set);
        if (range == null) return List.of();
        List<TargetSpec> result = new ArrayList<>();
        if (range[0] > TreasureCard.MIN_NUMBER) {
            for (CardColor c : CardColor.values()) result.add(new TargetSpec(c, range[0] - 1));
        }
        if (range[1] < TreasureCard.MAX_NUMBER) {
            for (CardColor c : CardColor.values()) result.add(new TargetSpec(c, range[1] + 1));
        }
        return result;
    }

    private static List<TargetSpec> nextTargetsRunColor(TreasureSet set) {
        int[] range = runRange(set);
        CardColor color = runColor(set);
        if (range == null || color == null) return List.of();
        List<TargetSpec> result = new ArrayList<>();
        if (range[0] > TreasureCard.MIN_NUMBER) result.add(new TargetSpec(color, range[0] - 1));
        if (range[1] < TreasureCard.MAX_NUMBER) result.add(new TargetSpec(color, range[1] + 1));
        return result;
    }

    private static int[] runRange(TreasureSet set) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (Card card : set.cards()) {
            if (card instanceof TreasureCard tc) {
                if (tc.number() < min) min = tc.number();
                if (tc.number() > max) max = tc.number();
            }
        }
        return min == Integer.MAX_VALUE ? null : new int[]{min, max};
    }

    private static CardColor runColor(TreasureSet set) {
        for (Card card : set.cards()) {
            if (card instanceof TreasureCard tc) return tc.color();
        }
        return null;
    }

    private static long countTreasure(List<Card> cards) {
        long count = 0;
        for (Card c : cards) if (c instanceof TreasureCard) count++;
        return count;
    }

    private static boolean inTargetList(TargetSpec t, List<Card> cards) {
        for (Card c : cards) {
            if (c instanceof TreasureCard tc && tc.color() == t.color() && tc.number() == t.number()) return true;
        }
        return false;
    }

    private record TargetSpec(CardColor color, int number) {}
}
