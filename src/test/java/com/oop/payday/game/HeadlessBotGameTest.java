package com.oop.payday.game;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.S6BotStrategy;
import com.oop.payday.bot.S7BotStrategy;
import com.oop.payday.log.PlayLogWriter;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.Player;

/**
 * 봇 대전이 UI 없이 끝까지 진행해 승자를 내는지(회귀 안전망)와 플레이 로그가 한 판을 온전히
 * 기록하는지 검증한다. 봇끼리 승률 A/B 측정은 더 이상 개선 척도가 아니므로(현재 전략은 S6) 두지 않는다.
 */
final class HeadlessBotGameTest {

    @Tag("integration")
    @Test
    void botsCanFinishPracticeGameWithoutUi() {
        AtomicReference<Team> winnerRef = new AtomicReference<>();

        Team alpha = team("테스트 봇 A");
        Team beta = team("테스트 봇 B");
        Game game = new Game(GameConfig.practice(true), alpha, beta, new GameListener() {
            @Override
            public void onGameOver(Team winner) {
                winnerRef.set(winner);
            }
        });

        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        Team winner = winnerRef.get();
        assertNotNull(winner, "헤드리스 봇 대전은 승자를 내고 종료해야 한다.");
        assertTrue(winner == alpha || winner == beta, "승자는 참가 팀 중 하나여야 한다.");
        assertTrue(winner.coins() >= GameConfig.PRACTICE_WIN,
                "승자는 연습 룰 승리 코인 이상을 보유해야 한다.");
    }

    /**
     * 플레이 로그({@link PlayLogWriter})가 한 판을 끝까지 잘 기록하는지 검증한다.
     * 메모리 {@link StringWriter} 로 받아 헤더·라운드·환금·종료 마커가 모두 남는지 확인 — 사용자 분석
     * 워크플로(사람이 플레이 → 로그 분석)가 의존하는 출력 형식의 회귀를 잡는 안전망이다.
     */
    @Tag("integration")
    @Test
    void playLogCapturesFullGame() {
        GameConfig config = GameConfig.practice(true);
        Team a = new Team("우리 팀", List.of(BotPlayer.test(new S6BotStrategy())));
        Team b = new Team("상대 팀", List.of(BotPlayer.test(new S6BotStrategy())));
        StringWriter buffer = new StringWriter();
        PlayLogWriter log = PlayLogWriter.to(buffer, config, a, b, a, b);

        Game game = new Game(config, a, b, log);
        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        String text = buffer.toString();
        assertTrue(text.contains("도적단의 월급날 — 플레이 로그"), "헤더가 있어야 한다.");
        assertTrue(text.contains("(S6)"), "플레이어 전략명이 기록돼야 한다.");
        assertTrue(text.contains("라운드 1"), "라운드 진행이 기록돼야 한다.");
        assertTrue(text.contains("[환금]"), "환금 페이즈가 기록돼야 한다.");
        assertTrue(text.contains("게임 종료 — 승리:"), "게임 종료가 기록돼야 한다.");
    }

    /**
     * S7(현재 기본 봇)이 한 판을 무효 행동 없이 끝까지 진행하는지 검증한다(회귀 안전망). S7 은 분할자·선택자
     * 역할을 번갈아 맡으므로 한 판으로 새 선택 모델·저주 부채·환금 보류 경로가 모두 행사된다.
     */
    @Tag("integration")
    @Test
    void s7BotFinishesPracticeGameWithoutInvalidActions() {
        AtomicReference<Team> winnerRef = new AtomicReference<>();

        Team alpha = new Team("S7 봇 A", List.of(BotPlayer.test(new S7BotStrategy())));
        Team beta = new Team("S7 봇 B", List.of(BotPlayer.test(new S7BotStrategy())));
        Game game = new Game(GameConfig.practice(true), alpha, beta, new GameListener() {
            @Override
            public void onGameOver(Team winner) {
                winnerRef.set(winner);
            }
        });

        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        Team winner = winnerRef.get();
        assertNotNull(winner, "S7 봇 대전은 승자를 내고 종료해야 한다.");
        assertTrue(winner == alpha || winner == beta, "승자는 참가 팀 중 하나여야 한다.");
        assertTrue(winner.coins() >= GameConfig.PRACTICE_WIN,
                "승자는 연습 룰 승리 코인 이상을 보유해야 한다.");
    }

    private static Team team(String name) {
        Player bot = BotPlayer.test(new S6BotStrategy());
        return new Team(name, List.of(bot));
    }
}
