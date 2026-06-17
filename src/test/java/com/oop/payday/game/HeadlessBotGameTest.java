package com.oop.payday.game;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.bot.S7BotStrategy;
import com.oop.payday.bot.S8BotStrategy;
import com.oop.payday.log.PlayLogWriter;
import com.oop.payday.model.Deck;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.Player;

/**
 * 봇 대전이 UI 없이 끝까지 진행해 승자를 내는지(회귀 안전망)와 플레이 로그가 한 판을 온전히
 * 기록하는지 검증한다. 시드 리포트는 사람 상대 강함의 주 지표가 아니라, 새 전략이 명백히 무너지지
 * 않았는지 보는 회귀 체온계로 둔다.
 */
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
     * 플레이 로그({@link PlayLogWriter})가 한 판을 끝까지 잘 기록하는지 검증한다.
     * 메모리 {@link StringWriter} 로 받아 헤더·라운드·환금·종료 마커가 모두 남는지 확인 — 사용자 분석
     * 워크플로(사람이 플레이 → 로그 분석)가 의존하는 출력 형식의 회귀를 잡는 안전망이다.
     */
    @Tag("integration")
    @Test
    void playLogCapturesFullGame() {
        GameConfig config = GameConfig.practice(true);
        Team a = new Team("우리 팀", List.of(BotPlayer.test(new S7BotStrategy())));
        Team b = new Team("상대 팀", List.of(BotPlayer.test(new S7BotStrategy())));
        StringWriter buffer = new StringWriter();
        PlayLogWriter log = PlayLogWriter.to(buffer, config, a, b, a, b);

        Game game = new Game(config, a, b, log);
        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        String text = buffer.toString();
        assertTrue(text.contains("도적단의 월급날 — 플레이 로그"), "헤더가 있어야 한다.");
        assertTrue(text.contains("(S7)"), "플레이어 전략명이 기록돼야 한다.");
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

    /**
     * S7(현재 기본 봇) 자가대전을 같은 seed 표본으로 돌린다.
     *
     * <p>봇끼리 승률은 사람 상대 강함의 척도가 아니므로, 이 테스트는 전략 개선을 증명하기보다
     * <b>회귀 체온계</b> 역할을 한다. 즉, 크래시 없이 모든 seed 를 끝내고 무효 환금이 게임당 평균 1회
     * 미만인지 확인하며, 평균 코인은 문서화할 참고 신호로 출력한다.
     */
    @Tag("integration")
    @Test
    void s7SelfPlaySeedReport() {
        runSeedReport("S7", S7BotStrategy::new, "S7", S7BotStrategy::new);
    }

    /**
     * S8(현재 기본 봇)이 한 판을 무효 행동 없이 끝까지 진행하는지 검증한다(회귀 안전망). S8 은 분할자·선택자
     * 역할을 번갈아 맡으므로 한 판으로 상대 확률 모델·카드 카운팅·실현 코인 종반 평가 경로가 모두 행사된다.
     */
    @Tag("integration")
    @Test
    void s8BotFinishesPracticeGameWithoutInvalidActions() {
        AtomicReference<Team> winnerRef = new AtomicReference<>();
        AtomicInteger invalidCashes = new AtomicInteger();

        Team alpha = new Team("S8 봇 A", List.of(BotPlayer.test(new S8BotStrategy())));
        Team beta = new Team("S8 봇 B", List.of(BotPlayer.test(new S8BotStrategy())));
        Game game = new Game(GameConfig.practice(true), alpha, beta, new GameListener() {
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

        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        Team winner = winnerRef.get();
        assertNotNull(winner, "S8 봇 대전은 승자를 내고 종료해야 한다.");
        assertTrue(winner == alpha || winner == beta, "승자는 참가 팀 중 하나여야 한다.");
        assertTrue(invalidCashes.get() == 0, "S8 은 무효 환금이 없어야 한다(현재 " + invalidCashes.get() + ").");
    }

    /**
     * S8(challenger) 대 S7(baseline 회귀) 자가대전을 같은 seed 표본으로 돌린다. 봇끼리 승률은 사람 상대
     * 강함의 척도가 아니라 <b>회귀 체온계</b>다 — S8 이 크래시·무효 환금 없이 모든 seed 를 끝내고 S7 대비
     * 터무니없이 무너지지 않는지 본다. 표본은 {@code -DbotSeedCount=300} 처럼 조절한다.
     */
    @Tag("integration")
    @Test
    void s8VsS7SeedReport() {
        runSeedReport("S8", S8BotStrategy::new, "S7", S7BotStrategy::new);
    }

    private static Team team(String name) {
        Player bot = BotPlayer.test(new S7BotStrategy());
        return new Team(name, List.of(bot));
    }

    /**
     * {@code challenger} 전략을 {@code baseline} 전략과 같은 seed 표본으로 맞붙여 승률·평균 코인·무효 환금을
     * 보고한다. 각 seed 는 선후공을 바꿔 2판씩 실행한다. 표본 수는 {@code -DbotSeedCount=300} 처럼 조절한다.
     */
    private static void runSeedReport(String challengerName, Supplier<BotStrategy> challenger,
            String baselineName, Supplier<BotStrategy> baseline) {
        List<MatchResult> results = new ArrayList<>();
        int seedCount = Integer.getInteger("botSeedCount", 20);
        for (int seed = 1; seed <= seedCount; seed++) {
            results.add(playSeededMatch(seed, true, challenger, baseline));
            results.add(playSeededMatch(seed, false, challenger, baseline));
        }

        long challengerWins = results.stream().filter(MatchResult::challengerWon).count();
        long baselineWins = results.size() - challengerWins;
        double averageRounds = results.stream().mapToInt(MatchResult::rounds).average().orElse(0.0);
        double challengerCoins = results.stream().mapToInt(MatchResult::challengerCoins).average().orElse(0.0);
        double baselineCoins = results.stream().mapToInt(MatchResult::baselineCoins).average().orElse(0.0);
        long totalInvalid = results.stream().mapToInt(MatchResult::invalidCashes).sum();
        double invalidPerGame = totalInvalid / (double) results.size();

        System.out.printf("%n%s vs %s standard seeded report%n", challengerName, baselineName);
        System.out.printf("games=%d %sWins=%d %sWins=%d %sWinRate=%.1f%%%n",
                results.size(), challengerName, challengerWins, baselineName, baselineWins,
                challengerName, challengerWins * 100.0 / results.size());
        System.out.printf("avgRounds=%.2f avg%sCoins=%.2f avg%sCoins=%.2f%n",
                averageRounds, challengerName, challengerCoins, baselineName, baselineCoins);
        System.out.printf("invalidCashes total=%d perGame=%.3f%n", totalInvalid, invalidPerGame);

        assertTrue(results.size() == seedCount * 2, "seed 표본(선후공 교대) 전부를 실행해야 한다.");
        assertTrue(results.stream().allMatch(result -> result.rounds() > 0),
                "모든 seed 게임은 최소 1라운드 이상 진행되어야 한다.");
        assertTrue(invalidPerGame < 1.0,
                "봇 무효 환금이 게임당 평균 1회 미만이어야 한다(현재 " + invalidPerGame + ").");
    }

    private static MatchResult playSeededMatch(int seed, boolean challengerFirst,
            Supplier<BotStrategy> challenger, Supplier<BotStrategy> baseline) {
        BotStrategy challengerStrategy = challenger.get();
        BotStrategy baselineStrategy = baseline.get();
        Team first = strategyTeam(challengerFirst ? "CH" : "BA",
                challengerFirst ? challengerStrategy : baselineStrategy);
        Team second = strategyTeam(challengerFirst ? "BA" : "CH",
                challengerFirst ? baselineStrategy : challengerStrategy);
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

        Team challengerTeam = challengerFirst ? first : second;
        Team baselineTeam = challengerFirst ? second : first;
        Team winner = winnerRef.get();
        assertNotNull(winner, "seed " + seed + " 게임은 승자를 내야 한다.");
        return new MatchResult(seed, challengerFirst, winner == challengerTeam, rounds[0],
                challengerTeam.coins(), baselineTeam.coins(), invalidCashes.get());
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

    private record MatchResult(int seed, boolean challengerFirst, boolean challengerWon, int rounds,
            int challengerCoins, int baselineCoins, int invalidCashes) {
    }
}
