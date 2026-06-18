package com.oop.payday.bot;

import java.util.List;

/**
 * 대기실에서 봇 슬롯마다 고를 수 있는 봇 전략 종류. {@link #create()} 로 실제 전략 인스턴스를 만든다.
 *
 * <p>현재 최신·기본 봇은 S8 하나만 노출한다.
 */
public enum BotKind {

    S8("S8 봇"),
    /**
     * LLM(제미나이) "말하는 상대" 봇. 1v1 전용이라 정규 대기실 드롭다운에는 노출하지 않고
     * ({@link #lobbyChoices()} 에서 제외), 메인 메뉴의 전용 진입점에서만 대사 싱크·API 키와 함께
     * {@code GameBoardController} 가 직접 생성한다.
     */
    LLM("LLM 봇");

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
            case LLM -> throw new UnsupportedOperationException(
                    "LLM 봇은 대사 싱크·API 키 주입이 필요해 GameBoardController 에서 생성한다");
        };
    }

    /** 정규 대기실 드롭다운에 노출할 봇 종류(LLM 은 1v1 전용이라 제외). */
    public static List<BotKind> lobbyChoices() {
        return List.of(S8);
    }
}
