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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.bot.HeuristicBotStrategy;
import com.oop.payday.bot.SmartBotStrategy;
import com.oop.payday.model.Deck;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.Player;

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

    @Tag("integration")
    @Test
    void smartBotVsHeuristicBotStandardSeedReport() {
        List<MatchResult> results = new ArrayList<>();
        for (int seed = 1; seed <= 20; seed++) {
            results.add(playSeededStandard(seed, true));
            results.add(playSeededStandard(seed, false));
        }

        long smartWins = results.stream().filter(MatchResult::smartWon).count();
        long heuristicWins = results.size() - smartWins;
        double averageRounds = results.stream().mapToInt(MatchResult::rounds).average().orElse(0.0);
        double smartAverageCoins = results.stream().mapToInt(MatchResult::smartCoins).average().orElse(0.0);
        double heuristicAverageCoins = results.stream().mapToInt(MatchResult::heuristicCoins).average().orElse(0.0);

        System.out.printf("%nSmart vs Heuristic standard seeded report%n");
        System.out.printf("games=%d smartWins=%d heuristicWins=%d smartWinRate=%.1f%%%n",
                results.size(), smartWins, heuristicWins, smartWins * 100.0 / results.size());
        System.out.printf("avgRounds=%.2f avgSmartCoins=%.2f avgHeuristicCoins=%.2f%n",
                averageRounds, smartAverageCoins, heuristicAverageCoins);
        results.forEach(result -> System.out.printf(
                "seed=%02d smartFirst=%-5s winner=%-9s rounds=%02d smart=%02d heuristic=%02d%n",
                result.seed(), result.smartFirst(), result.smartWon() ? "Smart" : "Heuristic",
                result.rounds(), result.smartCoins(), result.heuristicCoins()));

        assertTrue(results.size() == 40, "seed 표본 40판을 모두 실행해야 한다.");
        assertTrue(results.stream().allMatch(result -> result.rounds() > 0),
                "모든 seed 게임은 최소 1라운드 이상 진행되어야 한다.");
    }

    private static Team team(String name) {
        Player bot = BotPlayer.test(new SmartBotStrategy());
        return new Team(name, List.of(bot));
    }

    private static MatchResult playSeededStandard(int seed, boolean smartFirst) {
        BotStrategy smart = new SmartBotStrategy();
        BotStrategy heuristic = new HeuristicBotStrategy();
        Team first = strategyTeam(smartFirst ? "Smart" : "Heuristic", smartFirst ? smart : heuristic);
        Team second = strategyTeam(smartFirst ? "Heuristic" : "Smart", smartFirst ? heuristic : smart);
        AtomicReference<Team> winnerRef = new AtomicReference<>();
        int[] rounds = {0};
        Game game = new Game(GameConfig.standard(true), first, second, new GameListener() {
            @Override
            public void onRoundEnd(int round) {
                rounds[0] = round;
            }

            @Override
            public void onGameOver(Team winner) {
                winnerRef.set(winner);
            }
        });
        seedGame(game, first, second, seed);

        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        Team smartTeam = smartFirst ? first : second;
        Team heuristicTeam = smartFirst ? second : first;
        Team winner = winnerRef.get();
        assertNotNull(winner, "seed " + seed + " 게임은 승자를 내야 한다.");
        return new MatchResult(seed, smartFirst, winner == smartTeam, rounds[0],
                smartTeam.coins(), heuristicTeam.coins());
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

    private record MatchResult(int seed, boolean smartFirst, boolean smartWon, int rounds,
            int smartCoins, int heuristicCoins) {
    }
}
