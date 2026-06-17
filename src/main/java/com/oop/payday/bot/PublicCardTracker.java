package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.set.TreasureSet;

/**
 * 공개 정보로 재구성하는 <b>무상태</b> 카드 위치/카운팅 추적기(로드맵 4.2).
 *
 * <p>봇은 매 결정마다 컨텍스트의 공개 정보(내 보관·상대 보관·버림 더미)로 이 추적기를 새로 만든다
 * (이벤트 구독 없는 무상태 버전). 28장의 보물(4색 × 7숫자)과 1장의 와일드가 지금 어디 있는지
 * (내 손·상대 손·버림 더미·미지)를 표로 들고, "이 카드가 아직 살아 있나(미지 또는 버림 더미라
 * 드로우/슬쩍하기로 얻을 수 있나)", "이 세트를 한 장 키우는 데 평균 몇 턴 걸리나"를 답한다.
 *
 * <p>강한 봇은 순간 평가가 아니라 공개 카드 흐름을 계속 본다. 이 추적기로 (a) 성장 기대 턴을
 * 정확히 잡고, (b) 버림 더미에 와일드가 떨어졌는지(JUNK_DEALER 가치 폭증) 알고,
 * (c) 상대가 들고 있어 견제해야 하는지 / 이미 소모돼 기대가 없는지를 가린다.
 */
final class PublicCardTracker {

    /** 카드의 현재 위치. */
    enum Location { MINE, OPP, DISCARD, UNKNOWN }

    /** 버림 더미에 있는 카드: 슬쩍하기로 회수 가능 → 낮은 예상 턴. */
    private static final double DISCARD_TURNS = 1.5;
    /** 상대 손패에 있는 카드: 상대가 버리거나 환금할 때까지 대기. */
    private static final double OPP_TURNS = 5.0;
    /** 라운드당 분배되는 평균 보물 카드 수(5장 중 보물 비율 근사). */
    private static final double DRAWS_PER_ROUND = 2.5;

    private static final int MIN = TreasureCard.MIN_NUMBER;
    private static final int MAX = TreasureCard.MAX_NUMBER;
    private static final int COLORS = CardColor.values().length;
    /** 미지 상태 보물 카드 전체 수(4색 × 7숫자). */
    static final int TOTAL_TREASURE = COLORS * (MAX - MIN + 1);

    /** [색 ordinal][숫자 - MIN] 위치표. */
    private final Location[][] location;
    private final boolean wildInDiscard;
    private final int unknownTreasure;

    PublicCardTracker(List<Card> mine, List<Card> opponentHoldings, List<Card> discardPile) {
        this.location = new Location[COLORS][MAX - MIN + 1];
        for (Location[] row : location) {
            java.util.Arrays.fill(row, Location.UNKNOWN);
        }
        mark(opponentHoldings, Location.OPP);
        mark(discardPile, Location.DISCARD);
        mark(mine, Location.MINE); // 내 손이 가장 확실 — 마지막에 덮어쓴다.
        this.wildInDiscard = discardPile.stream().anyMatch(Card::isWild);

        int unknown = 0;
        for (Location[] row : location) {
            for (Location loc : row) {
                if (loc == Location.UNKNOWN) unknown++;
            }
        }
        this.unknownTreasure = unknown;
    }

    private void mark(List<Card> cards, Location loc) {
        for (Card card : cards) {
            if (card instanceof TreasureCard tc) {
                location[tc.color().ordinal()][tc.number() - MIN] = loc;
            }
        }
    }

    /** 한 장의 보물(색·숫자)이 지금 어디 있는지. */
    Location locationOf(CardColor color, int number) {
        if (number < MIN || number > MAX) return Location.UNKNOWN;
        return location[color.ordinal()][number - MIN];
    }

    /** 살아 있는 카드(미지 또는 버림 더미)인지 — 드로우/슬쩍하기로 아직 얻을 수 있다. */
    boolean isLive(CardColor color, int number) {
        Location loc = locationOf(color, number);
        return loc == Location.UNKNOWN || loc == Location.DISCARD;
    }

    /** 버림 더미에 와일드(굉장한 보물)가 떨어져 있나 — JUNK_DEALER 가치가 폭증한다. */
    boolean wildInDiscard() {
        return wildInDiscard;
    }

    /** 아직 어디 있는지 모르는(덱/미공개) 보물 카드 수. */
    int unknownTreasureCount() {
        return Math.max(1, unknownTreasure);
    }

    /**
     * 주어진 후보 카드들 중 가장 빠른 경로의 예상 획득 턴.
     * 버림 더미({@link #DISCARD_TURNS}) {@literal <} 상대 손({@link #OPP_TURNS}) {@literal <}
     * 미지(미지 보물 수 / (미지 후보 수 × {@link #DRAWS_PER_ROUND})). 후보가 없으면 사실상 무한.
     */
    double expectedTurnsForTargets(List<Target> targets) {
        if (targets.isEmpty()) return Double.MAX_VALUE / 2;
        boolean anyDiscard = false;
        boolean anyOpp = false;
        long unknownAlts = 0;
        for (Target t : targets) {
            switch (locationOf(t.color(), t.number())) {
                case DISCARD -> anyDiscard = true;
                case OPP -> anyOpp = true;
                case UNKNOWN -> unknownAlts++;
                case MINE -> { /* 이미 가진 카드는 목표가 아니다 */ }
            }
        }
        double best = Double.MAX_VALUE / 2;
        if (anyDiscard) best = Math.min(best, DISCARD_TURNS);
        if (anyOpp) best = Math.min(best, OPP_TURNS);
        if (unknownAlts > 0) best = Math.min(best, unknownTreasureCount() / (unknownAlts * DRAWS_PER_ROUND));
        return best;
    }

    /**
     * 이 세트를 한 장 더 키우는 데 필요한 카드의 예상 획득 턴(없으면 사실상 무한).
     * {@link CashInPlanOptimizer} 의 비공개 {@code expectedStepTurns} 를 공개 카드 위치 기반으로
     * 정식화한 판(로드맵 4.2).
     */
    double expectedTurnsToGrow(TreasureSet set) {
        return expectedTurnsForTargets(nextTargets(set));
    }

    /** 세트를 한 장 키울 때 필요한 카드 목록(세트 타입별). 이미 가진 카드는 위치표가 걸러낸다. */
    static List<Target> nextTargets(TreasureSet set) {
        int[] range = runRange(set);
        return switch (set.type()) {
            case SAME_NUMBER -> sameNumberTargets(set);
            case RUN -> range == null ? List.of() : runTargets(range, null);
            case RUN_SAME_COLOR -> range == null ? List.of() : runTargets(range, runColor(set));
        };
    }

    private static List<Target> sameNumberTargets(TreasureSet set) {
        int number = -1;
        Set<CardColor> used = new HashSet<>();
        for (Card card : set.cards()) {
            if (card instanceof TreasureCard tc) {
                number = tc.number();
                used.add(tc.color());
            }
        }
        if (number == -1) return List.of();
        List<Target> result = new ArrayList<>();
        for (CardColor color : CardColor.values()) {
            if (!used.contains(color)) result.add(new Target(color, number));
        }
        return result;
    }

    /** {@code color} 가 null 이면 색 무관 연속(모든 색), 아니면 같은 색 연속만. */
    private static List<Target> runTargets(int[] range, CardColor color) {
        List<Target> result = new ArrayList<>();
        if (range[0] > MIN) addRunTarget(result, range[0] - 1, color);
        if (range[1] < MAX) addRunTarget(result, range[1] + 1, color);
        return result;
    }

    private static void addRunTarget(List<Target> result, int number, CardColor color) {
        if (color != null) {
            result.add(new Target(color, number));
        } else {
            for (CardColor c : CardColor.values()) result.add(new Target(c, number));
        }
    }

    private static int[] runRange(TreasureSet set) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Card card : set.cards()) {
            if (card instanceof TreasureCard tc) {
                min = Math.min(min, tc.number());
                max = Math.max(max, tc.number());
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

    /** 키울 카드 후보 한 장(색·숫자). */
    record Target(CardColor color, int number) {}
}
