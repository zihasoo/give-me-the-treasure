package com.oop.payday.game;

/**
 * 진행 중인 게임 루프를 중단해야 할 때 던진다(재시작·메인 메뉴 나가기 등).
 *
 * <p>오프라인 사람({@code HumanPlayer})의 의사결정 대기나 동시 실행(간부/도우미 단계)이
 * 게임 스레드 인터럽트로 풀릴 때 발생하며, {@link Game#play()} 가 이를 잡아 판을 조용히 종료한다.
 * 네트워크 연결 해제는 {@link NetworkDisconnectedException} 가 같은 역할을 한다.
 */
public final class GameAbortedException extends RuntimeException {

    public GameAbortedException() {
        super("게임이 중단되었습니다");
    }
}
