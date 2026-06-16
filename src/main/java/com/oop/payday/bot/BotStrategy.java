package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 봇의 두뇌. 봇 의사결정의 단일 확장점이다(전략 패턴).
 *
 * <p>이 인터페이스만 구현하면 봇의 실력/방식을 통째로 교체할 수 있다:
 * 점수 기반({@link S6BotStrategy}), 난이도별 변형, 혹은 추후 LLM 기반 전략 등.
 * 게임 엔진과 {@code BotPlayer} 는 구체 구현을 모른 채 이 계약에만 의존한다.
 *
 * <p>각 메서드는 입력만으로 결정을 내리는 순수 함수에 가깝게 두어,
 * 동기 휴리스틱이든 비동기 호출(LLM)을 감싼 구현이든 끼울 수 있게 한다.
 */
public interface BotStrategy {

    /** 꾀부리기: 손패 5장과 코인 상황으로 분할 결정을 만든다. */
    SplitDecision decideSplit(SplitContext context);

    /** 분배: 두 묶음 중 가져갈 인덱스(0 또는 1). */
    int decideChoice(ChoiceContext context);

    /**
     * 분배(다인 팀): 팀이 가져간 카드({@code acquired})를 팀원끼리 나눈다(규칙서 §6-2-4).
     * {@code memberHoldings.get(i)} 는 i번째 멤버(0 = 리더)의 현재 보관 카드다.
     * 반환의 {@code byMember.get(i)} 는 그 멤버에게 새로 배정할 {@code acquired} 카드들이다.
     *
     * <p>기본 구현은 보관 수가 가장 적은 멤버에게 한 장씩 배분하는 균형 분배다(보유 한도
     * 초과 강제 처분을 피하고 멤버도 환금에 참여하게 함). 1v1은 이 메서드를 호출하지 않는다.
     */
    default TeamDistribution decideTeamDistribution(List<Card> acquired, List<List<Card>> memberHoldings) {
        int n = memberHoldings.size();
        List<List<Card>> byMember = new ArrayList<>();
        int[] counts = new int[n];
        for (int i = 0; i < n; i++) {
            byMember.add(new ArrayList<>());
            counts[i] = memberHoldings.get(i).size();
        }
        for (Card card : acquired) {
            int target = 0;
            for (int i = 1; i < n; i++) {
                if (counts[i] < counts[target]) {
                    target = i;
                }
            }
            byMember.get(target).add(card);
            counts[target]++;
        }
        return new TeamDistribution(byMember);
    }

    /** 준비: 도우미 후보 중 사용할 카드를 고른다. */
    List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount);

    /**
     * 환금 시작 시점의 snapshot으로 이번 환금 단계에서 시도할 행동 계획을 만든다.
     * {@code opponentCoins} 는 상대 팀 코인으로, 상대 승리 임박 시 즉시 환금 같은 종반 판단에 쓴다.
     * (네트워크 미러를 오염시키지 않도록 {@link CashInContext} 에 넣지 않고 별도 인자로 전달한다.)
     */
    List<CashInAction> planCashIn(CashInContext context, int opponentCoins);

    /** 화면/로그 표기에 쓰일 전략 이름(예: "규칙 기반"). */
    default String displayName() {
        return "봇";
    }
}
