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
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.officer.OfficerTile;
import com.oop.payday.model.set.SetEvaluator;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;

/**
 * 봇 전용 카드 평가기. 규칙 판정은 {@link SetEvaluator} 에 맡기고, 전략 판단에 쓸
 * "좋아 보이는 정도"만 계산한다.
 */
final class BotCardEvaluator {

    /** ALPHA 즉시 승리의 실현 코인 표현값 — 어떤 코인 마진도 압도하도록 크게 둔다. */
    static final int ALPHA_WIN_COIN = 1_000;

    private BotCardEvaluator() {
    }

    static int bestCashCoin(List<Card> cards) {
        return SetEvaluator.findBestSet(cards).map(TreasureSet::coin).orElse(0);
    }

    /**
     * 이 카드들을 지금 환금했을 때의 <b>실현 코인</b>(로드맵 4.4): 최선 세트 코인 + 손에 든 반응 도우미
     * 보너스(CUCKOO/LEO +3, LUCKY +7) + 리더 보너스(JOE 6코인↑ +1, WISE 상대저주≥2 +1).
     * ALPHA 즉승이 성립하면 {@link #ALPHA_WIN_COIN} 을 돌려, 종반 선택/분할 마진이 ALPHA 묶음을
     * 압도적으로 택하게 한다. 세트 코인만 보던 {@code bestCashCoin} 근사가 놓치던 알파/럭키/죠를 반영한다.
     *
     * @param helpers       이 플레이어가 가진(미사용) 도우미. 상대 평가 시엔 비공개라 빈 목록을 넘긴다.
     * @param officer       이 리더의 간부 타일(없으면 {@code null}). 리더 효과 비활성이면 {@code null}.
     * @param oppCurseCount 상대 보관 저주 수(WISE 판정용).
     */
    static int projectedCashCoin(List<Card> cards, List<HelperCard> helpers, OfficerTile officer, int oppCurseCount) {
        Optional<TreasureSet> best = SetEvaluator.findBestSet(cards);
        if (best.isEmpty()) {
            return 0;
        }
        TreasureSet set = best.get();
        int bonus = 0;
        for (HelperCard helper : helpers) {
            if (helper.isUsed()) {
                continue;
            }
            switch (helper.kind()) {
                case CUCKOO -> { if (set.type() == SetType.RUN_SAME_COLOR && set.size() >= 3) bonus += 3; }
                case LEO -> { if (set.type() == SetType.SAME_NUMBER && set.size() >= 3) bonus += 3; }
                case LUCKY -> { if (set.type() == SetType.RUN_SAME_COLOR && set.size() == 5) bonus += 7; }
                case ALPHA -> { if (isAlphaSet(set)) return ALPHA_WIN_COIN; }
                default -> { /* 비반응 도우미는 즉시 코인에 무관 */ }
            }
        }
        if (officer == OfficerTile.JOE && set.coin() >= 6) bonus += 1;
        if (officer == OfficerTile.WISE && oppCurseCount >= 2) bonus += 1;
        return set.coin() + bonus;
    }

    /** ALPHA 세트: 와일드 없이 숫자 1 보물 4장. */
    private static boolean isAlphaSet(TreasureSet set) {
        return set.size() == 4
                && set.cards().stream().noneMatch(Card::isWild)
                && set.cards().stream().allMatch(card ->
                        card instanceof TreasureCard treasure && treasure.number() == 1);
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
        if (card == null || card instanceof CursedCard) {
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
