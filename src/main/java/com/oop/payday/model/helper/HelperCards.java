package com.oop.payday.model.helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;

/**
 * 도우미 카드 9종 생성과 공통 판정 로직.
 */
public final class HelperCards {

    private HelperCards() {
    }

    public static List<HelperCard> shuffledDeck(Random random) {
        List<HelperCard> cards = new ArrayList<>();
        int id = 0;
        cards.add(cuckoo(id++));
        cards.add(leo(id++));
        cards.add(lucky(id++));
        cards.add(alpha(id++));
        cards.add(doug(id++));
        cards.add(tusker(id++));
        cards.add(viper(id++));
        cards.add(junkDealer(id++));
        cards.add(crocBrothers(id++));
        Collections.shuffle(cards, random);
        return cards;
    }

    private static HelperCard cuckoo(int id) {
        return new HelperCard(id, HelperKind.CUCKOO) {
            @Override
            public boolean canUse(HelperUseContext context) {
                return super.canUse(context) && isSameColorSet(context.lastCashedSet(), 3);
            }

            @Override
            protected void apply(HelperUseContext context) {
                context.addCoins(3);
                context.setMessage(displayName() + " — 3코인 획득");
            }
        };
    }

    private static HelperCard leo(int id) {
        return new HelperCard(id, HelperKind.LEO) {
            @Override
            public boolean canUse(HelperUseContext context) {
                TreasureSet set = context.lastCashedSet();
                return super.canUse(context) && set != null
                        && set.type() == SetType.SAME_NUMBER && set.size() >= 3;
            }

            @Override
            protected void apply(HelperUseContext context) {
                context.addCoins(3);
                context.setMessage(displayName() + " — 3코인 획득");
            }
        };
    }

    private static HelperCard lucky(int id) {
        return new HelperCard(id, HelperKind.LUCKY) {
            @Override
            public boolean canUse(HelperUseContext context) {
                return super.canUse(context) && isSameColorSet(context.lastCashedSet(), 5);
            }

            @Override
            protected void apply(HelperUseContext context) {
                context.addCoins(7);
                context.setMessage(displayName() + " — 7코인 획득");
            }
        };
    }

    private static HelperCard alpha(int id) {
        return new HelperCard(id, HelperKind.ALPHA) {
            @Override
            public boolean canUse(HelperUseContext context) {
                TreasureSet set = context.lastCashedSet();
                return super.canUse(context) && set != null && hasFourNaturalOnes(set);
            }

            @Override
            protected void apply(HelperUseContext context) {
                context.setInstantWinner(context.team());
                context.setMessage(displayName() + " — 숫자 1 네 장으로 즉시 승리");
            }
        };
    }

    private static HelperCard doug(int id) {
        return new HelperCard(id, HelperKind.DOUG) {
            @Override
            public boolean canUse(HelperUseContext context) {
                return super.canUse(context) && context.player().holdings().stream()
                        .anyMatch(c -> !(c instanceof CursedCard));
            }

            @Override
            protected void apply(HelperUseContext context) {
                int count = applyDoug(context);
                context.setMessage(displayName() + " — " + count + "장 교체");
            }
        };
    }

    /**
     * 더그 효과: 고른 카드(없으면 저주 아닌 보관 카드 전부)를 처분하고 같은 장수만큼 드로우.
     * 규칙서 §3-3 — "저주가 아닌 카드를 원하는 만큼 처분한 뒤 그만큼 드로우". 처분/드로우한 장수를 돌려준다.
     */
    private static int applyDoug(HelperUseContext context) {
        List<Card> selected = context.selectedCards();
        List<Card> targets = context.player().holdings().stream()
                .filter(c -> !(c instanceof CursedCard))
                .filter(c -> selected.isEmpty() || selected.contains(c))
                .toList();
        for (Card card : targets) {
            context.discard(card);
        }
        for (int i = 0; i < targets.size(); i++) {
            context.draw();
        }
        return targets.size();
    }

    private static HelperCard tusker(int id) {
        return new HelperCard(id, HelperKind.TUSKER) {
            @Override
            protected void apply(HelperUseContext context) {
                Card drawn = context.draw();
                context.suspendHoldLimitForRound();
                context.setMessage(displayName() + " — "
                        + (drawn == null ? "드로우 실패" : drawn.displayName() + " 획득")
                        + ", 이번 라운드 보유 한도 무시");
            }
        };
    }

    private static HelperCard viper(int id) {
        return new HelperCard(id, HelperKind.VIPER) {
            @Override
            public boolean canUse(HelperUseContext context) {
                return super.canUse(context) && context.player().holdings().stream()
                        .anyMatch(CursedCard.class::isInstance);
            }

            @Override
            protected void apply(HelperUseContext context) {
                List<Card> curses = context.player().holdings().stream()
                        .filter(CursedCard.class::isInstance)
                        .toList();
                int count = curses.size();
                for (Card card : curses) {
                    context.discard(card);
                }
                context.addCoins(count);
                context.setMessage(displayName() + " — 저주 " + count + "장 처분, " + count + "코인 획득");
            }
        };
    }

    private static HelperCard junkDealer(int id) {
        return new HelperCard(id, HelperKind.JUNK_DEALER) {
            @Override
            public boolean canUse(HelperUseContext context) {
                return super.canUse(context) && context.deck().discardView().stream().anyMatch(Card::isWild);
            }

            @Override
            protected void apply(HelperUseContext context) {
                Card wild = context.deck().takeWildFromDiscard();
                if (wild != null) {
                    context.player().receive(wild);
                }
                context.blockCashForRound();
                context.setMessage(displayName() + " — 굉장한 보물 회수, 이번 라운드 환금 금지");
            }
        };
    }

    private static HelperCard crocBrothers(int id) {
        return new HelperCard(id, HelperKind.CROC_BROTHERS) {
            @Override
            public boolean canUse(HelperUseContext context) {
                if (!super.canUse(context)) {
                    return false;
                }
                HelperCard target = context.copyTarget();
                if (target != null) {
                    return target.kind() != HelperKind.CROC_BROTHERS
                            && context.usedHelpers().contains(target)
                            && HelperRules.canCopy(target.kind(), context);
                }
                return !HelperRules.copyTargets(context).isEmpty();
            }

            @Override
            protected void apply(HelperUseContext context) {
                HelperCard target = context.copyTarget();
                if (target == null) {
                    target = HelperRules.copyTargets(context).stream()
                        .max(Comparator.comparingInt(HelperCard::id))
                        .orElseThrow();
                }
                copyEffect(target.kind(), context);
                context.setMessage(displayName() + " — " + target.displayName() + " 효과 복사");
            }
        };
    }

    private static void copyEffect(HelperKind kind, HelperUseContext context) {
        switch (kind) {
            case CUCKOO, LEO -> context.addCoins(3);
            case LUCKY -> context.addCoins(7);
            case ALPHA -> context.setInstantWinner(context.team());
            case DOUG -> applyDoug(context);
            case TUSKER -> {
                context.draw();
                context.suspendHoldLimitForRound();
            }
            case VIPER -> {
                List<Card> curses = context.player().holdings().stream()
                        .filter(CursedCard.class::isInstance)
                        .toList();
                for (Card card : curses) {
                    context.discard(card);
                }
                context.addCoins(curses.size());
            }
            case JUNK_DEALER -> {
                Card wild = context.deck().takeWildFromDiscard();
                if (wild != null) {
                    context.player().receive(wild);
                }
                context.blockCashForRound();
            }
            case CROC_BROTHERS -> throw new IllegalArgumentException("크록 형제는 복사 대상이 될 수 없습니다.");
        }
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
