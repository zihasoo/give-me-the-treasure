package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.set.SetEvaluator;
import com.oop.payday.model.set.TreasureSet;

/**
 * 봇 전용 카드 평가기. 규칙 판정은 {@link SetEvaluator} 에 맡기고, 전략 판단에 쓸
 * "좋아 보이는 정도"만 계산한다.
 */
final class BotCardEvaluator {

    private BotCardEvaluator() {
    }

    static int bestCashCoin(List<Card> cards) {
        return SetEvaluator.findBestSet(cards).map(TreasureSet::coin).orElse(0);
    }

    static int bundleScore(List<Card> cards) {
        return bestCashCoin(cards) * 100 + potentialScore(cards);
    }

    static int potentialScore(List<Card> cards) {
        int score = 0;
        int wild = 0;
        Map<Integer, Integer> numberCounts = new HashMap<>();
        Map<CardColor, Set<Integer>> colorNumbers = new EnumMap<>(CardColor.class);
        Set<Integer> curseNumbers = new HashSet<>();

        for (Card card : cards) {
            if (card.isWild()) {
                wild++;
                score += 38;
            } else if (card instanceof TreasureCard treasure) {
                numberCounts.merge(treasure.number(), 1, Integer::sum);
                colorNumbers.computeIfAbsent(treasure.color(), ignored -> new HashSet<>()).add(treasure.number());
                score += 4;
                if (treasure.number() == 1) {
                    score += 5;
                }
            } else if (card instanceof CursedCard cursed) {
                curseNumbers.add(cursed.number());
                score -= 18;
            } else {
                score -= 10;
            }
        }

        for (var entry : numberCounts.entrySet()) {
            int count = entry.getValue();
            if (count >= 2) {
                score += 18 + (count - 2) * 22;
            }
            if (entry.getKey() == 1 && count >= 2) {
                score += 12 + count * 4;
            }
            if (curseNumbers.contains(entry.getKey())) {
                score += 14;
            }
        }

        for (Set<Integer> numbers : colorNumbers.values()) {
            score += runPotential(numbers, wild, true);
        }

        Set<Integer> allNumbers = new HashSet<>(numberCounts.keySet());
        score += runPotential(allNumbers, wild, false);

        Optional<TreasureSet> best = SetEvaluator.findBestSet(cards);
        if (best.isPresent()) {
            score += best.get().coin() * 10;
        }
        return score;
    }

    static Card chooseDiscard(List<Card> cards) {
        Card worst = null;
        int worstLoss = Integer.MAX_VALUE;
        for (Card card : cards) {
            int loss = discardLoss(cards, card);
            if (loss < worstLoss) {
                worstLoss = loss;
                worst = card;
            }
        }
        return worst == null ? cards.get(cards.size() - 1) : worst;
    }

    static int discardLoss(List<Card> cards, Card card) {
        if (card instanceof CursedCard) {
            return -80;
        }
        List<Card> remaining = new ArrayList<>(cards);
        remaining.remove(card);
        int loss = potentialScore(cards) - potentialScore(remaining);
        if (card.isWild()) {
            loss += 60;
        }
        return loss;
    }

    private static int runPotential(Set<Integer> numbers, int wild, boolean sameColor) {
        if (numbers.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (int start = TreasureCard.MIN_NUMBER; start <= TreasureCard.MAX_NUMBER - 1; start++) {
            int maxLength = Math.min(5, TreasureCard.MAX_NUMBER - start + 1);
            for (int length = 2; length <= maxLength; length++) {
                int present = 0;
                for (int n = start; n < start + length; n++) {
                    if (numbers.contains(n)) {
                        present++;
                    }
                }
                int holes = length - present;
                if (present >= 2 && holes <= wild + 1) {
                    int base = sameColor ? 9 : 5;
                    score += base * present;
                    if (length >= 4) {
                        score += sameColor ? 12 : 7;
                    }
                    if (length == 5) {
                        score += sameColor ? 20 : 10;
                    }
                }
            }
        }
        return score;
    }
}
