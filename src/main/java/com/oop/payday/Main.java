package com.oop.payday;

import com.oop.payday.app.GameApp;

/**
 * 애플리케이션 진입점.
 *
 * <p>{@link javafx.application.Application} 을 직접 상속한 클래스를 main 으로 쓰면
 * 모듈 경로/클래스패스 실행 환경에 따라 JavaFX 런타임 인식 문제가 생길 수 있어,
 * 별도의 순수 런처 클래스를 두고 여기서 {@link GameApp} 을 띄운다.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        GameApp.main(args);
    }
}
