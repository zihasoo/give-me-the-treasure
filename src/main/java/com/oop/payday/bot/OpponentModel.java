package com.oop.payday.bot;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;

/**
 * 상대 선택 확률 모델(로드맵 4.3, 확률 prior 판). S7 은 분할을 평가할 때 상대(선택자)를
 * "합리적인 한 명"의 boolean({@code chooserTakesA})으로만 봤다. S8 은 상대가 각 묶음을 가져갈
 * <b>확률</b> {@code P(takesA)} 로 보고, 분할 기대값을 두 결과의 가중 평균으로 계산한다.
 *
 * <p>선호 가중치 벡터(즉시 코인·카드 물량·상대 손패 시너지·와일드 집착·뒷면 의심·저주 회피)로
 * 각 묶음의 효용을 매기고 softmax(온도 {@code temperature})로 확률을 낸다. {@link #DEFAULT} 는
 * S7 의 {@code predictedChooserValue} 가중을 그대로 옮긴 prior라, 온도를 0 으로 보내면 S7 의
 * boolean 예측과 일치한다. 같은 분할 후보라도 이 가중치(성향)가 다르면 점수가 달라진다.
 *
 * <p>이번 단계는 관찰/학습 배선 없이 prior만 쓴다(사용자 확정). 사람 로그로 가중치를 갱신하는
 * 학습은 후속 작업으로 남긴다.
 *
 * @param coinWeight       즉시 코인 1에 곱하는 가중(기본 100 = S7 의 ×100).
 * @param materialWeight   카드 1장의 재료 가치(기본 70 = S7 {@code CARD_MATERIAL}).
 * @param synergyPct       상대 손패 시너지(잠재력 증분)에 곱하는 비율(% , 기본 100).
 * @param wildPull         보이는/뒷면 와일드가 끌어당기는 효용(기본 600 = S7 {@code WILD_GRAB}).
 * @param hiddenPremium    뒷면 한 장이 더하는 효용(기본 130 = S7 의 물량 70 + 미지 60).
 * @param curseAversionPct 저주 1장이 무는 음의 재료 가치 비율(%, 기본 100 = 보물과 대칭).
 * @param temperature      softmax 온도. 클수록 선택이 평탄(불확실)해진다.
 */
record OpponentModel(int coinWeight, int materialWeight, int synergyPct, int wildPull,
        int hiddenPremium, int curseAversionPct, double temperature) {

    /** S7 의 선택자 예측을 그대로 옮긴 기본 prior(온도→0 이면 S7 boolean 과 동일). */
    static final OpponentModel DEFAULT = new OpponentModel(100, 70, 100, 600, 130, 100, 200.0);

    /**
     * 선택자가 묶음 A 를 가져갈 확률. 봇은 분할자라 뒷면 정체를 알므로 와일드 뒷면은 간파당한다고 본다
     * ({@code faceDownWildA/B}). S7 javadoc: 와일드는 어디 숨겨도 합리적 선택자가 가져간다.
     */
    double pTakesA(List<Card> visibleA, boolean hasFaceDownA, boolean faceDownWildA,
            List<Card> visibleB, boolean hasFaceDownB, boolean faceDownWildB, List<Card> oppHoldings) {
        double uA = utility(visibleA, hasFaceDownA, faceDownWildA, oppHoldings);
        double uB = utility(visibleB, hasFaceDownB, faceDownWildB, oppHoldings);
        return logistic((uA - uB) / Math.max(1e-6, temperature));
    }

    /** 한 묶음을 선택자가 얼마나 탐낼지의 효용(클수록 가져갈 확률↑). */
    double utility(List<Card> visible, boolean hasFaceDown, boolean faceDownWild, List<Card> oppHoldings) {
        double coin = (double) BotCardEvaluator.bestCashCoin(visible) * coinWeight;
        double material = 0;
        boolean visibleWild = false;
        for (Card card : visible) {
            if (card.isWild()) {
                visibleWild = true;
            } else if (card instanceof CursedCard) {
                material -= (double) materialWeight * curseAversionPct / 100;
            } else {
                material += materialWeight;
            }
        }
        double wild = (visibleWild || faceDownWild) ? wildPull : 0;
        double synergy = (double) synergy(oppHoldings, visible) * synergyPct / 100;
        double hidden = hasFaceDown ? hiddenPremium : 0;
        return coin + material + wild + synergy + hidden;
    }

    private static int synergy(List<Card> holdings, List<Card> bundle) {
        java.util.List<Card> combined = new java.util.ArrayList<>(holdings);
        combined.addAll(bundle);
        return BotCardEvaluator.potentialScore(combined) - BotCardEvaluator.potentialScore(holdings);
    }

    private static double logistic(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
