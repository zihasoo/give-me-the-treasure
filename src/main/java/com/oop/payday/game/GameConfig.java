package com.oop.payday.game;

/**
 * 한 판의 규칙 설정값. 연습 룰/정식 룰, 봇 상대 여부 등을 담는다.
 */
public final class GameConfig implements java.io.Serializable {

    /** 정식 룰 승리 코인. */
    public static final int STANDARD_WIN = 30;
    /** 연습 룰 승리 코인. */
    public static final int PRACTICE_WIN = 20;

    private final int winningCoins;
    private final boolean leaderEffectsEnabled;
    private final boolean vsBot;

    private GameConfig(int winningCoins, boolean leaderEffectsEnabled, boolean vsBot) {
        this.winningCoins = winningCoins;
        this.leaderEffectsEnabled = leaderEffectsEnabled;
        this.vsBot = vsBot;
    }

    /** 정식 룰: 30코인 승리 + 리더 효과 활성(M6). */
    public static GameConfig standard(boolean vsBot) {
        return new GameConfig(STANDARD_WIN, true, vsBot);
    }

    /** 연습 룰: 20코인 승리 + 리더 효과 비활성(규칙서 첫 플레이 추천). */
    public static GameConfig practice(boolean vsBot) {
        return new GameConfig(PRACTICE_WIN, false, vsBot);
    }

    public int winningCoins() {
        return winningCoins;
    }

    public boolean leaderEffectsEnabled() {
        return leaderEffectsEnabled;
    }

    public boolean vsBot() {
        return vsBot;
    }
}
