package com.oop.payday.bot;

/**
 * 대기실에서 봇 슬롯마다 고를 수 있는 봇 전략 종류. {@link #create()} 로 실제 전략 인스턴스를 만든다.
 *
 * <p>현재 최신·기본 봇은 S8 하나만 노출한다. 직전 세대 {@link S7BotStrategy} 는 클래스로 남아
 * {@code HeadlessBotGameTest} 의 회귀 baseline 으로만 쓰이고 대기실에는 노출하지 않는다.
 */
public enum BotKind {

    S8("S8 봇");

    private final String displayName;

    BotKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BotStrategy create() {
        return switch (this) {
            case S8 -> new S8BotStrategy();
        };
    }
}
