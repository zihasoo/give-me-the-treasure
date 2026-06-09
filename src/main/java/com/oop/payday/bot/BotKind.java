package com.oop.payday.bot;

/**
 * 대기실에서 봇 슬롯마다 고를 수 있는 봇 전략 종류. {@link #create()} 로 실제 전략 인스턴스를 만든다.
 */
public enum BotKind {

    SMART("스마트 봇", "조합 최적화까지 고려하는 강한 전략"),
    HEURISTIC("기본 봇", "규칙 기반의 무난한 전략");

    private final String displayName;
    private final String description;

    BotKind(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public BotStrategy create() {
        return switch (this) {
            case SMART -> new SmartBotStrategy();
            case HEURISTIC -> new HeuristicBotStrategy();
        };
    }
}
