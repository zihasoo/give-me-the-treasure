package com.oop.payday.decision;

/**
 * 환금 단계에서 한 플레이어의 행동을 게임 엔진에 제출하는 창구.
 *
 * <p>엔진이 환금 시작 시 플레이어별 sink를 만들어 {@code Player.beginCashIn} 으로 넘긴다.
 * 봇은 자기 스레드에서, 사람은 UI 입력으로, (미래)네트워크는 원격 응답으로 이 sink에 제출한다.
 * 모든 제출은 엔진의 단일 인박스로 직렬화되어 게임 스레드가 하나씩 처리하므로 락이 필요 없다.
 */
public interface CashSink {

    /** 행동 한 건을 제출한다. */
    void submit(CashInAction action);

    /** 턴 종료(패스)를 제출한다. */
    void pass();
}
