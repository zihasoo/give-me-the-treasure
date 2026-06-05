package com.oop.payday.game;

/**
 * 네트워크 대전 중 상대 연결이 끊겨 게임 루프를 중단해야 할 때 던진다.
 *
 * <p>{@code NetworkPlayer} 의 대기({@code decideXxx}) 나 환금 인박스 대기가
 * abort 로 풀릴 때 발생하며, {@link Game#play()} 가 이를 잡아 판을 조용히 종료한다.
 * 오프라인/봇 대전에서는 발생하지 않는다.
 */
public final class NetworkDisconnectedException extends RuntimeException {

    public NetworkDisconnectedException() {
        super("상대 연결이 끊어져 게임을 중단합니다");
    }
}
