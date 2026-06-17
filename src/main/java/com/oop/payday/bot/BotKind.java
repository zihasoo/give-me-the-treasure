package com.oop.payday.bot;

/**
 * 대기실에서 봇 슬롯마다 고를 수 있는 봇 전략 종류. {@link #create()} 로 실제 전략 인스턴스를 만든다.
 */
public enum BotKind {

    S7("S7 봇");

    private final String displayName;

    BotKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BotStrategy create() {
        return switch (this) {
            case S7 -> new S7BotStrategy();
        };
    }
}
