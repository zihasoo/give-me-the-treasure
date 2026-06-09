package com.oop.payday.decision;

import java.util.ArrayList;
import java.util.List;

import com.oop.payday.model.card.Card;

/**
 * 분배 단계에서 팀이 가져간 카드를 팀원끼리 나눈 결과(규칙서 §6-2-4 "팀 내에서 자유롭게 나눠 가진다").
 *
 * <p>{@code byMember.get(i)} 는 팀 멤버 리스트의 i번째 멤버(0 = 리더)가 보관할 카드들이다.
 * 1인 팀은 분배 단계를 건너뛰므로 다인(2인) 팀에서만 쓰인다.
 */
public record TeamDistribution(List<List<Card>> byMember) {

    public TeamDistribution {
        List<List<Card>> copy = new ArrayList<>();
        for (List<Card> share : byMember) {
            copy.add(List.copyOf(share));
        }
        byMember = List.copyOf(copy);
    }

    /** 리더(첫 멤버)가 모두 보관하고 나머지는 빈 손인 기본 분배. */
    public static TeamDistribution leaderTakesAll(List<Card> acquired, int memberCount) {
        List<List<Card>> byMember = new ArrayList<>();
        byMember.add(List.copyOf(acquired));
        for (int i = 1; i < memberCount; i++) {
            byMember.add(List.of());
        }
        return new TeamDistribution(byMember);
    }

    /** 모든 acquired 카드가 정확히 한 번씩, 멤버 수만큼의 묶음으로 나뉘었는지 검증. */
    public boolean isValid(List<Card> acquired, int memberCount) {
        if (byMember.size() != memberCount) {
            return false;
        }
        List<Card> remaining = new ArrayList<>(acquired);
        for (List<Card> share : byMember) {
            for (Card card : share) {
                if (!remaining.remove(card)) {
                    return false;
                }
            }
        }
        return remaining.isEmpty();
    }
}
