package com.oop.payday.bot;

import java.util.function.Consumer;

import com.oop.payday.llm.GeminiClient;

/**
 * 대기실에서 봇 슬롯마다 고를 수 있는 봇 전략 종류. {@link #create()} 로 실제 전략 인스턴스를 만든다.
 */
public enum BotKind {

    S8("S8 봇"),
    LLM("LLM 봇");

    private final String displayName;

    BotKind(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BotStrategy create() {
        return create(null, "");
    }

    public BotStrategy create(Consumer<String> say, String geminiApiKey) {
        return switch (this) {
            case S8 -> new S8BotStrategy();
            case LLM -> new LlmBotStrategy(
                    say,
                    new GeminiClient(geminiApiKey),
                    new S8BotStrategy());
        };
    }
}
