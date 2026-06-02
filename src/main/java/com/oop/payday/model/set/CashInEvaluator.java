package com.oop.payday.model.set;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;

/**
 * 환금 행동 판정. 세트 카드와 함께 선택한 저주받은 그림의 무료 처분까지 함께 검증한다.
 */
public final class CashInEvaluator {

    private CashInEvaluator() {
    }

    public static Optional<Result> evaluate(List<Card> cards) {
        List<Card> setCards = new ArrayList<>();
        List<CursedCard> cursedCards = new ArrayList<>();
        for (Card card : cards) {
            if (card instanceof CursedCard cursed) {
                cursedCards.add(cursed);
            } else {
                setCards.add(card);
            }
        }

        Optional<TreasureSet> set = SetEvaluator.evaluate(setCards);
        if (set.isEmpty()) {
            return Optional.empty();
        }

        Set<Integer> cursedNumbers = new HashSet<>();
        for (CursedCard cursed : cursedCards) {
            cursedNumbers.add(cursed.number());
        }
        if (!cursedNumbers.isEmpty() && representedNumberOptions(set.get(), setCards).stream()
                .noneMatch(numbers -> numbers.containsAll(cursedNumbers))) {
            return Optional.empty();
        }

        return Optional.of(new Result(set.get(), List.copyOf(cursedCards), List.copyOf(cards)));
    }

    private static List<Set<Integer>> representedNumberOptions(TreasureSet set, List<Card> setCards) {
        List<TreasureCard> treasures = new ArrayList<>();
        int wild = 0;
        for (Card card : setCards) {
            if (card instanceof TreasureCard treasure) {
                treasures.add(treasure);
            } else if (card.isWild()) {
                wild++;
            }
        }

        return switch (set.type()) {
            case SAME_NUMBER -> sameNumberOptions(treasures);
            case RUN, RUN_SAME_COLOR -> runOptions(treasures, wild, set.size());
        };
    }

    private static List<Set<Integer>> sameNumberOptions(List<TreasureCard> treasures) {
        if (treasures.isEmpty()) {
            List<Set<Integer>> all = new ArrayList<>();
            for (int number = TreasureCard.MIN_NUMBER; number <= TreasureCard.MAX_NUMBER; number++) {
                all.add(Set.of(number));
            }
            return all;
        }
        return List.of(Set.of(treasures.get(0).number()));
    }

    private static List<Set<Integer>> runOptions(List<TreasureCard> treasures, int wild, int size) {
        List<Set<Integer>> options = new ArrayList<>();
        Set<Integer> naturalNumbers = new HashSet<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (TreasureCard treasure : treasures) {
            int number = treasure.number();
            naturalNumbers.add(number);
            min = Math.min(min, number);
            max = Math.max(max, number);
        }

        int firstStart = treasures.isEmpty() ? TreasureCard.MIN_NUMBER : Math.max(TreasureCard.MIN_NUMBER, max - size + 1);
        int lastStart = treasures.isEmpty() ? TreasureCard.MAX_NUMBER - size + 1
                : Math.min(min, TreasureCard.MAX_NUMBER - size + 1);

        for (int start = firstStart; start <= lastStart; start++) {
            Set<Integer> run = new HashSet<>();
            for (int number = start; number < start + size; number++) {
                run.add(number);
            }
            if (!run.containsAll(naturalNumbers)) {
                continue;
            }
            int holes = size - naturalNumbers.size();
            if (holes == wild) {
                options.add(run);
            }
        }
        return options;
    }

    public record Result(TreasureSet set, List<CursedCard> freeCursedCards, List<Card> selectedCards) {
        public boolean hasFreeCursedCards() {
            return !freeCursedCards.isEmpty();
        }
    }
}
