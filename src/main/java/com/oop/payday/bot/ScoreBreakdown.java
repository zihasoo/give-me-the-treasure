package com.oop.payday.bot;

/**
 * 분할/선택 후보 한 개의 점수 항목별 내역(로드맵 4.1). 봇이 왜 그 수를 골랐는지 로그에서 바로
 * 보이게 하려고, 최종 행동뿐 아니라 후보별 점수 근거를 남긴다. 패배 로그를 볼 때 "가중치가 틀렸는지,
 * 평가 항목이 빠졌는지"를 이 표로 판단한다.
 *
 * @param label        후보 식별 문자열(예: 묶음 카드 요약)
 * @param coin         즉시 코인/실현 코인 기여분
 * @param synergy      보유 카드와의 시너지 기여분
 * @param hiddenEv     적대적 뒷면 기대값 기여분
 * @param wild         와일드 확보/견제 기여분
 * @param curse        저주 부채 기여분(음수)
 * @param deny         상대 견제 기여분(음수)
 * @param secureMargin 종반 실현 코인 마진 기여분
 * @param total        합산 점수
 * @param chosen       이 후보가 최종 선택됐는지
 */
record ScoreBreakdown(String label, long coin, long synergy, long hiddenEv, long wild,
        long curse, long deny, long secureMargin, long total, boolean chosen) {

    /** 로그 한 줄로 출력할 점수표 형식. */
    String toLogLine() {
        return String.format("%s %s coin=%d syn=%d hidden=%d wild=%d curse=%d deny=%d margin=%d total=%d",
                chosen ? "[*]" : "[ ]", label, coin, synergy, hiddenEv, wild, curse, deny, secureMargin, total);
    }
}
