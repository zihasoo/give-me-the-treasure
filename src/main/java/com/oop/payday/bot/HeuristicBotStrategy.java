package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperRules;
import com.oop.payday.model.set.CashInEvaluator;
import com.oop.payday.model.set.SetEvaluator;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;

/**
 * 규칙 기반 휴리스틱 봇. 세트 코인 가치를 기준으로 단순하지만 합리적인 결정을 내린다.
 *
 * <ul>
 *   <li><b>분할</b>: 모든 (2+3 / 1+4) 분할과 뒷면 후보를 전수 평가해, 선택 팀이
 *       공개 가치가 높은 묶음을 가져간 뒤 <i>분할 팀이 받게 될 묶음</i>의 실제 가치가
 *       최대가 되도록 고른다. 좋은 카드를 뒷면으로 숨겨 유도하는 효과가 자연히 나타난다.</li>
 *   <li><b>선택</b>: 공개 카드의 세트 가치가 높은 묶음을 가져간다(동률이면 카드 많은 쪽).</li>
 *   <li><b>환금</b>: 가능한 한 가장 비싼 세트부터 반복 환금한다.</li>
 * </ul>
 *
 * 블러핑/난이도 고도화는 별도 {@link BotStrategy} 구현으로 확장한다.
 */
public final class HeuristicBotStrategy implements BotStrategy {

    @Override
    public String displayName() {
        return "규칙 기반";
    }

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        List<Card> hand = context.hand();
        SplitDecision best = null;
        long bestScore = Long.MIN_VALUE;

        int n = hand.size(); // 5
        // 한 묶음을 size 1 또는 2 로 고른다(나머지가 4 또는 3). 모든 분할을 망라.
        for (int mask = 1; mask < (1 << n) - 1; mask++) {
            int count = Integer.bitCount(mask);
            if (count != 1 && count != 2) {
                continue;
            }
            List<Card> bundleA = new ArrayList<>();
            List<Card> bundleB = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    bundleA.add(hand.get(i));
                } else {
                    bundleB.add(hand.get(i));
                }
            }
            for (Card faceDown : hand) {
                long score = scoreSplitForSplitter(bundleA, bundleB, faceDown);
                if (score > bestScore) {
                    bestScore = score;
                    best = new SplitDecision(bundleA, bundleB, faceDown);
                }
            }
        }
        return best;
    }

    /**
     * 분할 후보의 가치를 평가한다. 선택 팀이 자기 이익을 위해 묶음을 고른다고 가정하고
     * (공개 가치가 큰 쪽, 동률이면 카드가 많은 쪽 — {@link #decideChoice} 와 동일),
     * 분할 팀이 받게 될 묶음의 실제 가치를 기준으로 점수를 매긴다.
     *
     * <p>우선순위: ① 코인 우위(내가 지키는 값 − 상대에게 주는 값) ② 내 진행도(내가 지키는 값)
     * ③ 균형 분할(2+3) 선호. ③ 덕분에 가치 차이가 없을 때 무의미한 1+4 분할을 남발하지 않는다.
     */
    private long scoreSplitForSplitter(List<Card> bundleA, List<Card> bundleB, Card faceDown) {
        int visibleA = coinValue(without(bundleA, faceDown));
        int visibleB = coinValue(without(bundleB, faceDown));
        // 선택 팀은 공개 가치가 큰 쪽(동률이면 카드 많은 쪽)을 가져가고, 분할 팀은 나머지를 받는다.
        boolean chooserTakesA = visibleA > visibleB
                || (visibleA == visibleB && bundleA.size() >= bundleB.size());
        List<Card> splitterBundle = chooserTakesA ? bundleB : bundleA;
        List<Card> chooserBundle = chooserTakesA ? bundleA : bundleB;

        int splitterValue = coinValue(splitterBundle);
        int chooserValue = coinValue(chooserBundle);
        boolean balanced = Math.min(bundleA.size(), bundleB.size()) == 2; // 2+3

        long score = (long) (splitterValue - chooserValue) * 10_000L; // ① 코인 우위
        score += (long) splitterValue * 10L;                          // ② 내 진행도
        score += balanced ? 1L : 0L;                                  // ③ 균형 분할(2+3) 선호
        return score;
    }

    @Override
    public int decideChoice(ChoiceContext context) {
        ChoiceView view = context.view();
        int best = 0;
        int bestValue = Integer.MIN_VALUE;
        int bestSize = -1;
        for (int i = 0; i < view.bundles().size(); i++) {
            var bundle = view.bundle(i);
            int value = coinValue(bundle.visibleCards());
            int size = bundle.size();
            if (value > bestValue || (value == bestValue && size > bestSize)) {
                best = i;
                bestValue = value;
                bestSize = size;
            }
        }
        return best;
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        return options.stream()
                .sorted(Comparator.comparingInt(this::helperPriority).reversed())
                .limit(chooseCount)
                .toList();
    }

    private int helperPriority(HelperCard helper) {
        return switch (helper.kind()) {
            case ALPHA -> 100;
            case LUCKY -> 90;
            case VIPER -> 80;
            case CUCKOO, LEO -> 70;
            case TUSKER -> 60;
            case JUNK_DEALER -> 50;
            case DOUG -> 40;
            case CROC_BROTHERS -> 30;
        };
    }

    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        List<CashInAction> actions = new ArrayList<>();
        List<Card> remaining = new ArrayList<>(context.holdings());
        Set<HelperCard> queuedHelpers = new HashSet<>();
        while (true) {
            Optional<TreasureSet> best = SetEvaluator.findBestSet(remaining);
            if (best.isEmpty()) {
                break;
            }
            List<Card> setCards = withMatchingCurses(best.get().cards(), remaining);
            List<HelperCard> cashHelpers = usefulConditionalHelpers(context.helpers(), queuedHelpers, best.get());
            actions.add(cashHelpers.isEmpty()
                    ? new CashInAction.Cash(setCards)
                    : new CashInAction.CashWithHelpers(setCards, cashHelpers));
            queuedHelpers.addAll(cashHelpers);
            remaining.removeAll(setCards);
        }
        addUsefulActionHelper(actions, context, remaining, queuedHelpers);
        if (!isTuskerQueued(actions)) {
            discardDownToLimit(actions, remaining, context.holdLimit());
        }
        return actions;
    }

    private List<HelperCard> usefulConditionalHelpers(List<HelperCard> helpers, Set<HelperCard> queuedHelpers,
            TreasureSet set) {
        List<HelperCard> result = new ArrayList<>();
        for (HelperCard helper : helpers) {
            if (helper.isUsed() || queuedHelpers.contains(helper)) {
                continue;
            }
            if (matchesConditionalHelper(helper.kind(), set)) {
                result.add(helper);
            }
        }
        return result;
    }

    private List<Card> withMatchingCurses(List<Card> setCards, List<Card> remaining) {
        List<Card> selected = new ArrayList<>(setCards);
        for (Card card : remaining) {
            if (card instanceof CursedCard && canCashWithCurse(selected, card)) {
                selected.add(card);
            }
        }
        return selected;
    }

    private boolean canCashWithCurse(List<Card> selected, Card cursed) {
        List<Card> candidate = new ArrayList<>(selected);
        candidate.add(cursed);
        return CashInEvaluator.evaluate(candidate).isPresent();
    }

    private boolean matchesConditionalHelper(HelperKind kind, TreasureSet set) {
        return switch (kind) {
            case CUCKOO -> set.type() == SetType.RUN_SAME_COLOR && set.size() >= 3;
            case LEO -> set.type() == SetType.SAME_NUMBER && set.size() >= 3;
            case LUCKY -> set.type() == SetType.RUN_SAME_COLOR && set.size() == 5;
            case ALPHA -> set.size() == 4
                    && set.cards().stream().noneMatch(Card::isWild)
                    && set.cards().stream().allMatch(c ->
                            c instanceof com.oop.payday.model.card.TreasureCard t && t.number() == 1);
            default -> false;
        };
    }

    private void addUsefulActionHelper(List<CashInAction> actions, CashInContext context, List<Card> remaining,
            Set<HelperCard> queuedHelpers) {
        for (HelperCard helper : context.helpers()) {
            if (helper.isUsed() || queuedHelpers.contains(helper) || HelperRules.isCashReaction(helper.kind())) {
                continue;
            }
            HelperCard copyTarget = null;
            boolean use = switch (helper.kind()) {
                case VIPER -> remaining.stream().filter(CursedCard.class::isInstance).count() >= 1;
                case TUSKER -> remaining.size() <= context.holdLimit();
                case DOUG -> SetEvaluator.findBestSet(remaining).isEmpty()
                        && remaining.stream().filter(c -> !(c instanceof CursedCard)).count() >= 2;
                case JUNK_DEALER -> context.discardPile().stream().anyMatch(Card::isWild);
                case CROC_BROTHERS -> {
                    copyTarget = chooseCrocTarget(context, remaining);
                    yield copyTarget != null;
                }
                default -> false;
            };
            if (use) {
                actions.add(new CashInAction.UseHelper(helper, copyTarget));
                queuedHelpers.add(helper);
                return;
            }
        }
    }

    private HelperCard chooseCrocTarget(CashInContext context, List<Card> remaining) {
        return context.usedHelpers().stream()
                .filter(h -> h.kind() != HelperKind.CROC_BROTHERS)
                .filter(h -> canCopyNow(h.kind(), context, remaining))
                .findFirst()
                .orElse(null);
    }

    private boolean canCopyNow(HelperKind kind, CashInContext context, List<Card> remaining) {
        return switch (kind) {
            case TUSKER -> true;
            case VIPER -> remaining.stream().anyMatch(CursedCard.class::isInstance);
            case JUNK_DEALER -> context.discardPile().stream().anyMatch(Card::isWild);
            case DOUG -> remaining.stream().anyMatch(c -> !(c instanceof CursedCard));
            default -> false;
        };
    }

    private boolean isTuskerQueued(List<CashInAction> actions) {
        return actions.stream().anyMatch(action -> switch (action) {
            case CashInAction.UseHelper use -> use.helper().kind() == HelperKind.TUSKER;
            case CashInAction.CashWithHelpers cash -> cash.helpers().stream()
                    .anyMatch(helper -> helper.kind() == HelperKind.TUSKER);
            default -> false;
        });
    }

    private void discardDownToLimit(List<CashInAction> actions, List<Card> remaining, int holdLimit) {
        List<Card> projected = new ArrayList<>(remaining);
        if (actions.stream().anyMatch(action -> switch (action) {
            case CashInAction.UseHelper use -> use.helper().kind() == HelperKind.VIPER;
            case CashInAction.CashWithHelpers cash -> cash.helpers().stream()
                    .anyMatch(helper -> helper.kind() == HelperKind.VIPER);
            default -> false;
        })) {
            projected.removeIf(CursedCard.class::isInstance);
        }
        while (projected.size() > holdLimit) {
            Card discard = chooseDiscard(projected);
            actions.add(new CashInAction.Discard(discard));
            projected.remove(discard);
            remaining.remove(discard);
        }
    }

    private Card chooseDiscard(List<Card> cards) {
        return cards.stream()
                .filter(c -> !(c instanceof CursedCard))
                .findFirst()
                .orElse(cards.get(cards.size() - 1));
    }

    /** 카드 목록으로 만들 수 있는 최고 세트의 코인값(없으면 0). */
    private int coinValue(List<Card> cards) {
        return SetEvaluator.findBestSet(cards).map(TreasureSet::coin).orElse(0);
    }

    /** 리스트에서 특정 카드 1장을 제외한 새 리스트(참조 동일성 기준). */
    private List<Card> without(List<Card> cards, Card excluded) {
        List<Card> copy = new ArrayList<>(cards);
        copy.remove(excluded);
        return copy;
    }
}
