package com.oop.payday.model.set;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.TreasureCard;

/**
 * 세트 유효성 판정과 환금표 적용을 담당하는 순수 로직 유틸리티.
 *
 * <p>핵심 규칙(규칙서 §7):
 * <ul>
 *   <li>같은 숫자: 2~4장, 숫자 동일.</li>
 *   <li>연속된 숫자: 3~5장, 숫자 연속(색 무관). 7→1 순환 불가.</li>
 *   <li>연속 + 같은 색: 3~5장, 숫자 연속 + 색 동일(코인 더 많음).</li>
 * </ul>
 * 굉장한 보물(와일드)은 부족한 색·숫자를 메워 세트를 완성하는 것으로 처리한다.
 * 저주받은 그림·슬쩍하기 등 세트 불가 카드가 섞이면 무효.
 */
public final class SetEvaluator {

    private SetEvaluator() {
    }

    /**
     * 주어진 카드 묶음이 정확히 하나의 세트를 이루는지 판정한다.
     * 같은 묶음이 여러 종류로 성립하면 코인이 가장 큰 종류를 채택한다.
     *
     * @return 유효한 세트면 {@link TreasureSet}, 아니면 비어 있음
     */
    public static Optional<TreasureSet> evaluate(List<Card> cards) {
        if (cards.size() < 2) {
            return Optional.empty();
        }

        int wild = 0;
        List<TreasureCard> treasures = new ArrayList<>();
        for (Card c : cards) {
            if (c.isWild()) {
                wild++;
            } else if (c instanceof TreasureCard t) {
                treasures.add(t);
            } else {
                return Optional.empty(); // 저주받은 그림·슬쩍하기 등은 세트 불가
            }
        }

        int size = cards.size();
        SetType bestType = null;
        int bestCoin = SetType.INVALID;

        for (SetType type : SetType.values()) {
            if (!type.isValidSize(size)) {
                continue;
            }
            if (!matches(type, treasures, wild, size)) {
                continue;
            }
            int coin = type.coin(size);
            if (coin > bestCoin) {
                bestCoin = coin;
                bestType = type;
            }
        }

        if (bestType == null) {
            return Optional.empty();
        }
        return Optional.of(new TreasureSet(cards, bestType, bestCoin));
    }

    /**
     * 보유 카드 중에서 코인이 가장 큰 환금 세트를 찾는다(부분집합 탐색).
     * 환금/봇 의사결정 보조용.
     *
     * @return 환금 가능한 최고 세트, 없으면 비어 있음
     */
    public static Optional<TreasureSet> findBestSet(List<Card> holdings) {
        // 세트에 쓰일 수 있는 카드(보물 + 와일드)만 후보로 추린다.
        List<Card> usable = new ArrayList<>();
        for (Card c : holdings) {
            if (c.canFormSet() || c.isWild()) {
                usable.add(c);
            }
        }

        TreasureSet best = null;
        int n = usable.size();
        // 부분집합 전수 탐색 (보유 카드 수가 작아 비용이 충분히 낮다)
        for (int mask = 1; mask < (1 << n); mask++) {
            int bits = Integer.bitCount(mask);
            if (bits < 2 || bits > 5) {
                continue;
            }
            List<Card> subset = new ArrayList<>(bits);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(usable.get(i));
                }
            }
            Optional<TreasureSet> set = evaluate(subset);
            if (set.isPresent() && (best == null || set.get().coin() > best.coin())) {
                best = set.get();
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean matches(SetType type, List<TreasureCard> treasures, int wild, int size) {
        return switch (type) {
            case SAME_NUMBER -> isSameNumber(treasures);
            case RUN -> isRun(treasures, wild, size);
            case RUN_SAME_COLOR -> isRun(treasures, wild, size) && isSameColor(treasures);
        };
    }

    /** 일반 보물들의 숫자가 모두 같은가(와일드는 그 숫자로 맞출 수 있어 무관). */
    private static boolean isSameNumber(List<TreasureCard> treasures) {
        if (treasures.isEmpty()) {
            return true; // 전부 와일드 — 장수 유효성은 호출부에서 판단
        }
        int n = treasures.get(0).number();
        return treasures.stream().allMatch(t -> t.number() == n);
    }

    /** 일반 보물들의 색이 모두 같은가(와일드는 그 색으로 맞출 수 있어 무관). */
    private static boolean isSameColor(List<TreasureCard> treasures) {
        if (treasures.isEmpty()) {
            return true;
        }
        CardColor c = treasures.get(0).color();
        return treasures.stream().allMatch(t -> t.color() == c);
    }

    /**
     * 일반 보물 + 와일드 {@code wild}장으로 길이 {@code size}의 연속 구간을
     * [1,7] 안에서 만들 수 있는가. (7→1 순환은 허용하지 않음)
     */
    private static boolean isRun(List<TreasureCard> treasures, int wild, int size) {
        // 일반 보물 숫자에 중복이 있으면 연속 불가
        boolean[] seen = new boolean[TreasureCard.MAX_NUMBER + 1];
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (TreasureCard t : treasures) {
            int num = t.number();
            if (seen[num]) {
                return false;
            }
            seen[num] = true;
            min = Math.min(min, num);
            max = Math.max(max, num);
        }

        if (treasures.isEmpty()) {
            // 와일드만으로는 의미 있는 연속을 만들 수 없다(와일드는 최대 1장)
            return wild >= size && size <= TreasureCard.MAX_NUMBER;
        }

        // 모든 일반 보물을 포함하는 길이 size 의 연속 구간 [start, start+size-1] 이
        // [1,7] 안에 존재하는지 확인. 빈 자리(size - 보물수)는 와일드가 채운다.
        if (max - min > size - 1) {
            return false; // 보물들이 너무 벌어져 한 구간에 못 들어감
        }
        for (int start = Math.max(1, max - (size - 1));
                start <= Math.min(min, TreasureCard.MAX_NUMBER - (size - 1));
                start++) {
            // 이 구간이면 모든 보물이 안에 들고, 남는 자리 수가 곧 필요한 와일드 수
            int holes = size - treasures.size();
            if (holes == wild) {
                return true;
            }
        }
        return false;
    }
}
