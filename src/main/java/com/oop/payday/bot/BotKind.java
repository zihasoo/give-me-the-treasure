package com.oop.payday.bot;

import java.util.function.Consumer;

import com.oop.payday.llm.GeminiClient;

/**
 * 대기실에서 봇 슬롯마다 고를 수 있는 봇 전략 종류. {@link #create()} 로 실제 전략 인스턴스를 만든다.
 */
public enum BotKind {

    EASY("쉬움"),
    NORMAL("중간"),
    HARD("어려움"),
    LLM("LLM");

    private final String displayName;

    BotKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public String numberedName(int number) {
        return displayName + " 봇 " + number;
    }

    public BotStrategy create() {
        return create(null, "");
    }

    public BotStrategy create(Consumer<String> say, String geminiApiKey) {
        return switch (this) {
            case EASY -> new DifficultyAdjustedBotStrategy(DifficultyAdjustedBotStrategy.Level.EASY);
            case NORMAL -> new DifficultyAdjustedBotStrategy(DifficultyAdjustedBotStrategy.Level.NORMAL);
            case HARD -> new DifficultyAdjustedBotStrategy(DifficultyAdjustedBotStrategy.Level.HARD);
            case LLM -> new LlmBotStrategy(
                    say,
                    new GeminiClient(geminiApiKey));
        };
    }
}
