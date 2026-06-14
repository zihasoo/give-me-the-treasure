package com.oop.payday.game;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.bot.S1BotStrategy;
import com.oop.payday.bot.S2BotStrategy;
import com.oop.payday.model.Deck;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.Player;

final class HeadlessBotGameTest {

    /** 무효 환금이 표면화되는 안내 메시지 조각({@code Game.applyCash}). */
    private static final String INVALID_CASH_MARKER = "무효한 환금 무시됨";

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
     * 실험 전략({@link S2BotStrategy}, S2)을 기존 강한 전략({@link S1BotStrategy}, S1)과 맞붙여
     * A/B 측정한다. 휴리스틱 봇은 너무 약해 개선 효과 검증의 기준선으로 부적절하므로 S1을 기준선으로 쓴다.
     * 아울러 봇이 제출한 무효 환금이 과도하지 않은지(게임당 평균) 함께 검증한다.
     */
    @Tag("integration")
    @Test
    void S1vsS2SeedReport() {
        List<MatchResult> results = new ArrayList<>();
        for (int seed = 1; seed <= 20; seed++) {
            results.add(playSeededStandard(seed, true));
            results.add(playSeededStandard(seed, false));
        }

        long s2Wins = results.stream().filter(MatchResult::s2Won).count();
        long s1Wins = results.size() - s2Wins;
        double averageRounds = results.stream().mapToInt(MatchResult::rounds).average().orElse(0.0);
        double s2AverageCoins = results.stream().mapToInt(MatchResult::s2Coins).average().orElse(0.0);
        double s1AverageCoins = results.stream().mapToInt(MatchResult::s1Coins).average().orElse(0.0);
        long totalInvalid = results.stream().mapToInt(MatchResult::invalidCashes).sum();
        double invalidPerGame = totalInvalid / (double) results.size();

        System.out.printf("%nS2 vs S1 standard seeded report%n");
        System.out.printf("games=%d s2Wins=%d s1Wins=%d s2WinRate=%.1f%%%n",
                results.size(), s2Wins, s1Wins, s2Wins * 100.0 / results.size());
        System.out.printf("avgRounds=%.2f avgS2Coins=%.2f avgS1Coins=%.2f%n",
                averageRounds, s2AverageCoins, s1AverageCoins);
        System.out.printf("invalidCashes total=%d perGame=%.3f%n", totalInvalid, invalidPerGame);
        results.forEach(result -> System.out.printf(
                "seed=%02d s2First=%-5s winner=%-2s rounds=%02d s2=%02d s1=%02d invalid=%d%n",
                result.seed(), result.s2First(), result.s2Won() ? "S2" : "S1",
                result.rounds(), result.s2Coins(), result.s1Coins(), result.invalidCashes()));

        assertTrue(results.size() == 40, "seed 표본 40판을 모두 실행해야 한다.");
        assertTrue(results.stream().allMatch(result -> result.rounds() > 0),
                "모든 seed 게임은 최소 1라운드 이상 진행되어야 한다.");
        assertTrue(invalidPerGame < 1.0,
                "봇 무효 환금이 게임당 평균 1회 미만이어야 한다(현재 " + invalidPerGame + ").");
    }

    private static Team team(String name) {
        Player bot = BotPlayer.test(new S1BotStrategy());
        return new Team(name, List.of(bot));
    }

    private static MatchResult playSeededStandard(int seed, boolean s2First) {
        BotStrategy s2 = new S2BotStrategy();
        BotStrategy s1 = new S1BotStrategy();
        Team first = strategyTeam(s2First ? "S2" : "S1", s2First ? s2 : s1);
        Team second = strategyTeam(s2First ? "S1" : "S2", s2First ? s1 : s2);
        AtomicReference<Team> winnerRef = new AtomicReference<>();
        AtomicInteger invalidCashes = new AtomicInteger();
        int[] rounds = {0};
        Game game = new Game(GameConfig.standard(true), first, second, new GameListener() {
            @Override
            public void onRoundEnd(int round) {
                rounds[0] = round;
            }

            @Override
            public void onMessage(String message) {
                if (message.contains(INVALID_CASH_MARKER)) {
                    invalidCashes.incrementAndGet();
                }
            }

            @Override
            public void onGameOver(Team winner) {
                winnerRef.set(winner);
            }
        });
        seedGame(game, first, second, seed);

        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        Team s2Team = s2First ? first : second;
        Team s1Team = s2First ? second : first;
        Team winner = winnerRef.get();
        assertNotNull(winner, "seed " + seed + " 게임은 승자를 내야 한다.");
        return new MatchResult(seed, s2First, winner == s2Team, rounds[0],
                s2Team.coins(), s1Team.coins(), invalidCashes.get());
    }

    private static Team strategyTeam(String name, BotStrategy strategy) {
        return new Team(name, List.of(BotPlayer.test(strategy)));
    }

    private static void seedGame(Game game, Team first, Team second, int seed) {
        try {
            setField(game, "splitTeam", first);
            setField(game, "chooseTeam", second);
            setField(game, "random", new Random(seed * 1_000_003L + 17));
            setField(game, "deck", new Deck(new Random(seed * 1_000_003L + 31)));

            Method setupOfficers = Game.class.getDeclaredMethod("setupOfficers");
            setupOfficers.setAccessible(true);
            setupOfficers.invoke(game);

            Method configureHoldLimits = Game.class.getDeclaredMethod("configureHoldLimits", Team.class);
            configureHoldLimits.setAccessible(true);
            configureHoldLimits.invoke(game, first);
            configureHoldLimits.invoke(game, second);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("seed 주입 실패", e);
        }
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record MatchResult(int seed, boolean s2First, boolean s2Won, int rounds,
            int s2Coins, int s1Coins, int invalidCashes) {
    }
}
