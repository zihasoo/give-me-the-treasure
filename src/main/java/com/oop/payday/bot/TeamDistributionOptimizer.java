package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.card.Card;

/**
 * 2v2 팀 분배 최적화기(로드맵 4.5). 기본 균형 분배(카드 수가 적은 멤버에게 한 장씩)는 세트 완성을
 * 흩뜨려 약하다. 이 최적화기는 <b>세트를 완성·성장시킬 카드를 한 멤버에게 몰아주는</b> 것을 우선한다.
 *
 * <p>각 카드를 어느 멤버에게 줄지, 그 멤버 보유 카드에 더했을 때의 {@link BotCardEvaluator#potentialScore}
 * 증분이 가장 큰 쪽으로 배정한다. potentialScore 는 (a) 같은 숫자/색 연속 완성에 큰 가점, (b) 저주를
 * <i>같은 숫자 보물을 든</i> 멤버에게 두면 무료 처분 여지로 덜 깎임(저주 라우팅), (c) 숫자 1 보물 가점
 * (ALPHA 빌더에 1 몰아주기)을 이미 반영하므로, 단일 점수로 4.5 의 규칙들이 자연히 만족된다.
 *
 * <p>한 멤버에게만 몰리면 보유 한도 초과 강제 처분이 나므로, 멤버가 이미 받은 만큼 가벼운 균형
 * 페널티를 둬 시너지가 없을 땐 흩는다.
 */
final class TeamDistributionOptimizer {

    /** 멤버가 카드 1장을 더 받을 때마다 무는 균형 페널티(시너지 없을 때만 분산되도록 작게). */
    private static final int BALANCE_PENALTY = 2;

    private TeamDistributionOptimizer() {
    }

    static TeamDistribution distribute(List<Card> acquired, List<List<Card>> memberHoldings) {
        int n = memberHoldings.size();
        if (n <= 1) {
            return new TeamDistribution(List.of(new ArrayList<>(acquired)));
        }

        List<List<Card>> working = new ArrayList<>();
        List<List<Card>> result = new ArrayList<>();
        for (List<Card> holding : memberHoldings) {
            working.add(new ArrayList<>(holding));
            result.add(new ArrayList<>());
        }

        // 시너지가 큰 카드부터 배정해 자기 자리를 먼저 차지하게 한다(균형 페널티에 밀리지 않도록).
        List<Card> ordered = new ArrayList<>(acquired);
        ordered.sort(Comparator.comparingInt((Card c) -> bestMarginal(working, c)).reversed());

        for (Card card : ordered) {
            int target = 0;
            int bestValue = Integer.MIN_VALUE;
            for (int m = 0; m < n; m++) {
                int value = marginalGain(working.get(m), card) - working.get(m).size() * BALANCE_PENALTY;
                if (value > bestValue) {
                    bestValue = value;
                    target = m;
                }
            }
            working.get(target).add(card);
            result.get(target).add(card);
        }
        return new TeamDistribution(result);
    }

    /** 이 카드를 어느 멤버에게든 줬을 때 얻을 수 있는 최대 시너지 증분(배정 순서 정렬용). */
    private static int bestMarginal(List<List<Card>> working, Card card) {
        int best = Integer.MIN_VALUE;
        for (List<Card> holding : working) {
            best = Math.max(best, marginalGain(holding, card));
        }
        return best;
    }

    /** 멤버 보유 카드에 이 카드를 더했을 때의 잠재력 증분. */
    private static int marginalGain(List<Card> holding, Card card) {
        List<Card> combined = new ArrayList<>(holding);
        combined.add(card);
        return BotCardEvaluator.potentialScore(combined) - BotCardEvaluator.potentialScore(holding);
    }
}
