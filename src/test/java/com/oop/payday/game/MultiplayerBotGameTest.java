package com.oop.payday.game;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.HeuristicBotStrategy;
import com.oop.payday.bot.S1BotStrategy;
import com.oop.payday.player.BotPlayer;

/**
 * 3·4인 다인 팀(한 팀 최대 2명)에서도 봇 대전이 끝까지 진행해 승자를 내는지 검증한다.
 * 팀 내 카드/도우미 분배(규칙서 §6-2-4, §4-3)가 엔진에서 정상 동작하는지 회귀 보호한다.
 */
final class MultiplayerBotGameTest {

    @Tag("integration")
    @Test
    void fourPlayerTwoVsTwoFinishes() {
        Team alpha = new Team("팀 A", List.of(
                BotPlayer.test(new S1BotStrategy()),
                BotPlayer.test(new HeuristicBotStrategy())));
        Team beta = new Team("팀 B", List.of(
                BotPlayer.test(new S1BotStrategy()),
                BotPlayer.test(new HeuristicBotStrategy())));
        assertGameFinishes(alpha, beta);
    }

    @Tag("integration")
    @Test
    void threePlayerOneVsTwoFinishes() {
        Team solo = new Team("팀 A", List.of(BotPlayer.test(new S1BotStrategy())));
        Team duo = new Team("팀 B", List.of(
                BotPlayer.test(new S1BotStrategy()),
                BotPlayer.test(new HeuristicBotStrategy())));
        assertGameFinishes(solo, duo);
    }

    private static void assertGameFinishes(Team a, Team b) {
        AtomicReference<Team> winnerRef = new AtomicReference<>();
        Game game = new Game(GameConfig.practice(true), a, b, new GameListener() {
            @Override
            public void onGameOver(Team winner) {
                winnerRef.set(winner);
            }
        });

        assertTimeoutPreemptively(Duration.ofSeconds(10), game::play);

        Team winner = winnerRef.get();
        assertNotNull(winner, "다인 봇 대전은 승자를 내고 종료해야 한다.");
        assertTrue(winner == a || winner == b, "승자는 참가 팀 중 하나여야 한다.");
        assertTrue(winner.coins() >= GameConfig.PRACTICE_WIN,
                "승자는 연습 룰 승리 코인 이상을 보유해야 한다.");
    }
}
