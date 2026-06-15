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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.oop.payday.bot.BotStrategy;
import com.oop.payday.bot.HeuristicBotStrategy;
import com.oop.payday.bot.S1BotStrategy;
import com.oop.payday.bot.S2BotStrategy;
import com.oop.payday.bot.S3BotStrategy;
import com.oop.payday.bot.S3BotStrategy.S3Tuning;
import com.oop.payday.bot.S4BotStrategy;
import com.oop.payday.bot.S5BotStrategy;
import com.oop.payday.bot.S6BotStrategy;
import com.oop.payday.log.PlayLogWriter;
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
     * 플레이 로그({@link PlayLogWriter})가 한 판을 끝까지 잘 기록하는지 검증한다.
     * 메모리 {@link StringWriter} 로 받아 헤더·라운드·환금·종료 마커가 모두 남는지 확인 — 사용자 분석
     * 워크플로(사람이 플레이 → 로그 분석)가 의존하는 출력 형식의 회귀를 잡는 안전망이다.
     */
    @Tag("integration")
    @Test
    void playLogCapturesFullGame() {
        GameConfig config = GameConfig.practice(true);
        Team a = new Team("우리 팀", List.of(BotPlayer.test(new S6BotStrategy())));
        Team b = new Team("상대 팀", List.of(BotPlayer.test(new S5BotStrategy())));
        StringWriter buffer = new StringWriter();
        PlayLogWriter log = PlayLogWriter.to(buffer, config, a, b, a, b);

        Game game = new Game(config, a, b, log);
        assertTimeoutPreemptively(Duration.ofSeconds(5), game::play);

        String text = buffer.toString();
        assertTrue(text.contains("도적단의 월급날 — 플레이 로그"), "헤더가 있어야 한다.");
        assertTrue(text.contains("(S6)") && text.contains("(S5)"), "플레이어 전략명이 기록돼야 한다.");
        assertTrue(text.contains("라운드 1"), "라운드 진행이 기록돼야 한다.");
        assertTrue(text.contains("[환금]"), "환금 페이즈가 기록돼야 한다.");
        assertTrue(text.contains("게임 종료 — 승리:"), "게임 종료가 기록돼야 한다.");
    }

    /**
     * 실험 전략({@link S2BotStrategy}, S2)을 기존 강한 전략({@link S1BotStrategy}, S1)과 맞붙여
     * A/B 측정한다. 휴리스틱 봇은 너무 약해 개선 효과 검증의 기준선으로 부적절하므로 S1을 기준선으로 쓴다.
     * 아울러 봇이 제출한 무효 환금이 과도하지 않은지(게임당 평균) 함께 검증한다.
     */
    @Tag("integration")
    @Test
    void S1vsS2SeedReport() {
        runSeedReport("S2", S2BotStrategy::new, "S1", S1BotStrategy::new);
    }

    /**
     * 상황 컨텍스트 확장 전략({@link S3BotStrategy}, S3)을 직전 버전({@link S2BotStrategy}, S2)과 맞붙여
     * A/B 측정한다. 시너지·적응형 선택자·승리 확정 분할·상대 임박 환금의 효과를 동일 seed 로 비교하고,
     * 무효 환금 견고성(게임당 평균)이 유지되는지 함께 확인한다.
     */
    @Tag("integration")
    @Test
    void S2vsS3SeedReport() {
        runSeedReport("S3", S3BotStrategy::new, "S2", S2BotStrategy::new);
    }

    /**
     * 실험 전략({@link S4BotStrategy}, S4)을 직전 버전({@link S3BotStrategy}, S3)과 맞붙여 A/B 측정한다.
     * S4는 환금 보류(§4.1)와 상대 holdings 견제(§4.2)를 추가한 버전이다.
     * 무효 환금 견고성이 유지되는지(게임당 평균 1회 미만)도 함께 확인한다.
     */
    @Tag("integration")
    @Test
    void S3vsS4SeedReport() {
        runSeedReport("S4", S4BotStrategy::new, "S3", S3BotStrategy::new);
    }

    /**
     * 실험 전략({@link S5BotStrategy}, S5)을 직전 버전({@link S4BotStrategy}, S4)과 맞붙여 A/B 측정한다.
     * S5는 보류 판단을 "성장 코인 증분(이진)" 에서 "예상 획득 턴 당 기대 코인 증분(연속 점수)"으로 개선했다.
     * 상대 손패가 opponentHoldings를 통해 expectedStepTurns에 반영된다.
     */
    @Tag("integration")
    @Test
    void S4vsS5SeedReport() {
        runSeedReport("S5", S5BotStrategy::new, "S4", S4BotStrategy::new);
    }

    /**
     * 실험 전략({@link S6BotStrategy}, S6)을 직전 버전({@link S5BotStrategy}, S5)과 맞붙인다.
     * S6는 현실적 선택자 모델·상시 maximin·저주 라우팅·환금 도우미 강화를 추가한 버전이다.
     * 봇끼리 승률은 더 이상 "사람 상대 강함"의 척도가 아니므로(문서 §4.3) 이 리포트는 <b>회귀 안전망</b>
     * — 봇이 크래시 없이 게임을 끝내고 무효 환금이 게임당 평균 1회 미만인지 검증 — 으로만 둔다.
     */
    @Tag("integration")
    @Test
    void S5vsS6SeedReport() {
        runSeedReport("S6", S6BotStrategy::new, "S5", S5BotStrategy::new);
    }

    /**
     * S3 파라미터 <b>자기복제 스윕</b>: 기본 S3({@link S3Tuning#DEFAULT}) 대비 각 변형의 승률을 같은
     * seed 표본으로 잰다. 순진한 S2가 아니라 동급(강한) 상대에게 통하는 방향을 찾기 위함이다(사람 상대 프록시).
     * 첫 줄(DEFAULT vs DEFAULT)은 노이즈 바닥(≈50%) 대조군. 실제 튜닝은 {@code -DbotSeedCount} 로 표본을 키워 본다.
     */
    @Tag("integration")
    @Test
    void S3ParamSweep() {
        List<S3Tuning> variants = List.of(
                S3Tuning.DEFAULT,                  // 대조군 — ≈50% 여야 함(노이즈 바닥)
                new S3Tuning(20, 40, 0, 100),      // 종반 구간 ↓
                new S3Tuning(25, 40, 0, 100),
                new S3Tuning(40, 40, 0, 100),      // 종반 구간 ↑
                new S3Tuning(50, 40, 0, 100),
                new S3Tuning(30, 0, 0, 100),       // 블러핑 끄기
                new S3Tuning(30, 80, 0, 100),      // 블러핑 ↑
                new S3Tuning(30, 120, 0, 100),
                new S3Tuning(30, 40, 50, 100),     // maximin 견고성(종반) ↑
                new S3Tuning(30, 40, 100, 100),
                new S3Tuning(30, 40, 0, 0),        // 견제 끄기
                new S3Tuning(30, 40, 0, 200));     // 견제 ↑

        int seeds = Integer.getInteger("botSeedCount", 20);
        int games = seeds * 2;
        long totalInvalid = 0;
        System.out.printf("%nS3 self-play sweep vs DEFAULT (seeds=%d, games/variant=%d)%n", seeds, games);
        for (S3Tuning v : variants) {
            int wins = 0;
            int invalid = 0;
            for (int seed = 1; seed <= seeds; seed++) {
                MatchResult a = playSeededMatch(seed, true, () -> new S3BotStrategy(v), S3BotStrategy::new);
                MatchResult b = playSeededMatch(seed, false, () -> new S3BotStrategy(v), S3BotStrategy::new);
                wins += (a.challengerWon() ? 1 : 0) + (b.challengerWon() ? 1 : 0);
                invalid += a.invalidCashes() + b.invalidCashes();
            }
            totalInvalid += invalid;
            System.out.printf("(end=%2d lure=%3d mm=%3d deny=%3d)  win=%3d/%-3d %5.1f%%  invalid=%d%n",
                    v.endzonePct(), v.lureWeight(), v.maximinWeight(), v.denyWeight(),
                    wins, games, wins * 100.0 / games, invalid);
        }
        assertTrue(totalInvalid / (double) (variants.size() * (long) games) < 1.0,
                "스윕 전반 무효 환금이 게임당 평균 1회 미만이어야 한다.");
    }

    /**
     * {@code challenger} 전략을 {@code baseline} 전략과 같은 seed 표본 40판(선후공 교대)으로 맞붙여
     * 승률·평균 코인·무효 환금을 보고한다. 기준선 회귀(무효 환금 급증)를 잡는 안전망이기도 하다.
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
        // results.forEach(result -> System.out.printf(
        //         "seed=%02d chFirst=%-5s winner=%-2s rounds=%02d %s=%02d %s=%02d invalid=%d%n",
        //         result.seed(), result.challengerFirst(), result.challengerWon() ? challengerName : baselineName,
        //         result.rounds(), challengerName, result.challengerCoins(),
        //         baselineName, result.baselineCoins(), result.invalidCashes()));

        assertTrue(results.size() == seedCount * 2, "seed 표본(선후공 교대) 전부를 실행해야 한다.");
        assertTrue(results.stream().allMatch(result -> result.rounds() > 0),
                "모든 seed 게임은 최소 1라운드 이상 진행되어야 한다.");
        assertTrue(invalidPerGame < 1.0,
                "봇 무효 환금이 게임당 평균 1회 미만이어야 한다(현재 " + invalidPerGame + ").");
    }

    private static Team team(String name) {
        Player bot = BotPlayer.test(new S1BotStrategy());
        return new Team(name, List.of(bot));
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
