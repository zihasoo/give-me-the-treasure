package com.oop.payday.model.helper;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;

/**
 * 도우미 사용 가능 여부를 UI, 봇, 엔진이 같은 기준으로 판단하기 위한 규칙 모음.
 */
public final class HelperRules {

    private HelperRules() {
    }

    public enum Timing {
        CASH_REACTION,
        STANDALONE
    }

    public record Availability(boolean usable, String reason, List<HelperCard> copyTargets) {
        public Availability(boolean usable, String reason) {
            this(usable, reason, List.of());
        }
    }

    public static Timing timing(HelperKind kind) {
        return switch (kind) {
            case CUCKOO, LEO, LUCKY, ALPHA -> Timing.CASH_REACTION;
            case DOUG, TUSKER, VIPER, JUNK_DEALER, CROC_BROTHERS -> Timing.STANDALONE;
        };
    }

    public static boolean isCashReaction(HelperKind kind) {
        return timing(kind) == Timing.CASH_REACTION;
    }

    public static Availability availability(HelperCard helper, HelperUseContext context) {
        if (helper.isUsed()) {
            return new Availability(false, "이미 사용한 도우미입니다.");
        }
        return switch (helper.kind()) {
            case CUCKOO -> isSameColorSet(context.lastCashedSet(), 3)
                    ? usable()
                    : blocked("같은 색깔 카드 3장 이상 세트를 환금해야 합니다.");
            case LEO -> {
                TreasureSet set = context.lastCashedSet();
                yield set != null && set.type() == SetType.SAME_NUMBER && set.size() >= 3
                        ? usable()
                        : blocked("같은 숫자 카드 3장 이상 세트를 환금해야 합니다.");
            }
            case LUCKY -> isSameColorSet(context.lastCashedSet(), 5)
                    ? usable()
                    : blocked("같은 색깔 카드 5장 세트를 환금해야 합니다.");
            case ALPHA -> context.lastCashedSet() != null && hasFourNaturalOnes(context.lastCashedSet())
                    ? usable()
                    : blocked("와일드 없이 숫자 1 보물 4장을 환금해야 합니다.");
            case DOUG -> context.player().holdings().stream().anyMatch(c -> !(c instanceof CursedCard))
                    ? usable()
                    : blocked("저주가 아닌 보관 카드가 필요합니다.");
            case TUSKER -> usable();
            case VIPER -> context.player().holdings().stream().anyMatch(CursedCard.class::isInstance)
                    ? usable()
                    : blocked("저주받은 그림을 보유해야 합니다.");
            case JUNK_DEALER -> context.deck().discardView().stream().anyMatch(Card::isWild)
                    ? usable()
                    : blocked("버림 더미에 굉장한 보물이 있어야 합니다.");
            case CROC_BROTHERS -> {
                List<HelperCard> targets = copyTargets(context);
                yield targets.isEmpty()
                        ? new Availability(false, "복사 가능한 사용 완료 도우미가 없습니다.", targets)
                        : new Availability(true, "복사할 도우미를 선택하세요.", targets);
            }
        };
    }

    public static List<HelperCard> copyTargets(HelperUseContext context) {
        return context.usedHelpers().stream()
                .filter(h -> h.kind() != HelperKind.CROC_BROTHERS && canCopy(h.kind(), context))
                .toList();
    }

    public static boolean canCopy(HelperKind kind, HelperUseContext context) {
        return switch (kind) {
            case CUCKOO -> isSameColorSet(context.lastCashedSet(), 3);
            case LEO -> {
                TreasureSet set = context.lastCashedSet();
                yield set != null && set.type() == SetType.SAME_NUMBER && set.size() >= 3;
            }
            case LUCKY -> isSameColorSet(context.lastCashedSet(), 5);
            case ALPHA -> context.lastCashedSet() != null && hasFourNaturalOnes(context.lastCashedSet());
            case DOUG -> context.player().holdings().stream().anyMatch(c -> !(c instanceof CursedCard));
            case TUSKER -> true;
            case VIPER -> context.player().holdings().stream().anyMatch(CursedCard.class::isInstance);
            case JUNK_DEALER -> context.deck().discardView().stream().anyMatch(Card::isWild);
            case CROC_BROTHERS -> false;
        };
    }

    private static Availability usable() {
        return new Availability(true, "사용할 수 있습니다.");
    }

    private static Availability blocked(String reason) {
        return new Availability(false, reason);
    }

    private static boolean isSameColorSet(TreasureSet set, int minSize) {
        return set != null && set.type() == SetType.RUN_SAME_COLOR && set.size() >= minSize;
    }

    private static boolean hasFourNaturalOnes(TreasureSet set) {
        if (set.size() != 4 || set.cards().stream().anyMatch(Card::isWild)) {
            return false;
        }
        return set.cards().stream()
                .filter(TreasureCard.class::isInstance)
                .map(TreasureCard.class::cast)
                .allMatch(card -> card.number() == 1);
    }
}
