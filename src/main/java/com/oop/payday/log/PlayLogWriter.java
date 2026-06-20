package com.oop.payday.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.oop.payday.decision.CashInContext;
import com.oop.payday.game.GameConfig;
import com.oop.payday.game.GameListener;
import com.oop.payday.game.Phase;
import com.oop.payday.game.Team;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.Player;

/**
 * 한 판의 진행을 사람이 읽기 좋은 텍스트 로그로 파일에 남기는 {@link GameListener}.
 *
 * <p>목적은 <b>사람 vs 봇 게임을 사후 분석</b>하는 것이다 — 봇의 분할·선택·환금이 무방비/쪼잔하지
 * 않은지, 저주 떠넘김·대박 보호가 일어나는지 등을 로그만 보고 판단할 수 있게 한다. 봇의 <i>내부 점수</i>는
 * 기록하지 않는다(봇과 분리); 결정 시점의 <b>공개 상태 + 스냅샷</b>(분할자 손패, 양 팀 보관·코인, 제시된
 * 묶음, 분배 결과, 환금 세트)을 남겨, 전략 소스로 판단 근거를 재구성할 수 있게 한다.
 *
 * <p>콜백은 모두 게임 스레드에서 단일 순서로 호출되므로 별도 동기화는 두지 않는다. 쓰기는 best-effort —
 * I/O 오류는 표준 오류로만 알리고 게임을 막지 않는다. 매 줄 flush 하여 게임을 중도에 닫아도 로그가 남는다.
 */
public final class PlayLogWriter implements GameListener {

    private final Writer out;
    private final Path path;
    private final Team teamA;
    private final Team teamB;
    private final Map<Player, String> tags = new IdentityHashMap<>();
    private boolean closed;

    private PlayLogWriter(Writer out, Path path, GameConfig config, Team teamA, Team teamB,
            Team firstSplit, Team firstChoose) {
        this.out = out;
        this.path = path;
        this.teamA = teamA;
        this.teamB = teamB;
        cacheTags(teamA);
        cacheTags(teamB);
        writeHeader(config, teamA, teamB, firstSplit, firstChoose);
    }

    /**
     * 게임용 로거를 만든다. {@code -Dpayday.playlog=true} 면 켜지고, 기본값은 꺼짐.
     * 파일은 작업 디렉터리의 {@code logs/play-<timestamp>.log} 에 생성한다.
     */
    public static PlayLogWriter createForGame(GameConfig config, Team teamA, Team teamB,
            Team firstSplit, Team firstChoose) {
        if (!Boolean.parseBoolean(System.getProperty("payday.playlog", "false"))) {
            return null;
        }
        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path file = dir.resolve("play-" + stamp + ".log");
            BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
            return new PlayLogWriter(writer, file, config, teamA, teamB, firstSplit, firstChoose);
        } catch (IOException e) {
            return null;
        }
    }

    /** @deprecated use {@link #createForGame(GameConfig, Team, Team, Team, Team)} */
    @Deprecated
    public static PlayLogWriter createForOfflineGame(GameConfig config, Team teamA, Team teamB,
            Team firstSplit, Team firstChoose) {
        return createForGame(config, teamA, teamB, firstSplit, firstChoose);
    }

    /** 임의 {@link Writer} 로 기록하는 로거(테스트·대체 출력용). 파일을 만들지 않는다. */
    public static PlayLogWriter to(Writer out, GameConfig config, Team teamA, Team teamB,
            Team firstSplit, Team firstChoose) {
        return new PlayLogWriter(out, null, config, teamA, teamB, firstSplit, firstChoose);
    }

    /** 생성된 로그 파일 경로(절대 경로). 사용자 안내용. {@link #to}로 만든 경우 "(메모리)". */
    public String pathString() {
        return path == null ? "(메모리)" : path.toAbsolutePath().toString();
    }

    // ─── 헤더 ─────────────────────────────────────────────────────────────────────

    private void cacheTags(Team team) {
        for (Player p : team.members()) {
            String kind = p instanceof BotPlayer bot ? bot.strategyName() : "사람";
            tags.put(p, team.name() + "/" + p.name() + "(" + kind + ")");
        }
    }

    private void writeHeader(GameConfig config, Team teamA, Team teamB, Team firstSplit, Team firstChoose) {
        line("=== 도적단의 월급날 — 플레이 로그 ===");
        line("시작: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        line("승리 코인: " + config.winningCoins() + (config.leaderEffectsEnabled() ? " (정식 룰)" : " (연습 룰)"));
        line("선분할: " + firstSplit.name() + " / 선선택: " + firstChoose.name());
        line("플레이어:");
        for (Team team : List.of(teamA, teamB)) {
            for (Player p : team.members()) {
                String kind = p instanceof BotPlayer bot ? bot.strategyName() : "사람";
                line("  [" + team.name() + "] " + p.name() + " (" + kind + ")");
            }
        }
    }

    // ─── 이벤트 ───────────────────────────────────────────────────────────────────

    @Override
    public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        if (phase == Phase.SCHEME) {
            line("");
            line("──────── 라운드 " + round + " (분할: " + splitTeam.name() + ") ────────");
            line("[" + phase.korean() + "]");
            dumpHoldings();
        } else {
            line("[" + phase.korean() + "]");
            if (phase == Phase.CASH_IN) {
                dumpHoldings();
            }
        }
    }

    @Override
    public void onHandDealt(Player splitter, List<Card> hand) {
        line("  분할자 " + tag(splitter) + " 손패: " + cards(hand));
    }

    @Override
    public void onChoiceReady(BundlePair bundles) {
        line("  제시된 묶음:");
        line("    묶음0: [공개] " + cards(bundles.visible0()) + faceDownNote(bundles.faceDown0()));
        line("    묶음1: [공개] " + cards(bundles.visible1()) + faceDownNote(bundles.faceDown1()));
    }

    @Override
    public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        line("  분배: 선택 팀(" + chooseTeam.name() + ")이 묶음" + chosenIndex + " 선택");
        line("    " + chooseTeam.name() + " 획득: " + cards(chooseCards) + "  (뒷면 공개됨)");
        line("    " + splitTeam.name() + " 획득: " + cards(splitCards));
    }

    @Override
    public void onCashIn(Player player, TreasureSet set) {
        line("  환금 " + tag(player) + ": " + set);
    }

    @Override
    public void onHelperUsed(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        StringBuilder sb = new StringBuilder("  도우미 " + tag(player) + ": " + helper.displayName());
        if (message != null && !message.isBlank()) {
            sb.append(" — ").append(message);
        }
        if (drawn != null && !drawn.isEmpty()) {
            sb.append(" [드로우: ").append(cards(drawn)).append("]");
        }
        if (discarded != null && !discarded.isEmpty()) {
            sb.append(" [처분: ").append(cards(discarded)).append("]");
        }
        line(sb.toString());
    }

    @Override
    public void onDiscard(Player player, Card card) {
        line("  처분 " + tag(player) + ": " + card.displayName());
    }

    @Override
    public void onForcedDiscard(Player player, List<Card> cards) {
        line("  강제 처분 " + tag(player) + "(한도 초과): " + cards(cards));
    }

    @Override
    public void onStealActivated(Player player, Card drawnCard) {
        line("  슬쩍하기 " + tag(player) + ": " + (drawnCard == null ? "덱 소진" : drawnCard.displayName() + " 획득"));
    }

    @Override
    public void onCoinsChanged(Team team, int delta) {
        if (delta == 0) {
            return;
        }
        line("  코인 변동: " + team.name() + " " + (delta > 0 ? "+" : "") + delta + " (→" + team.coins() + ")");
    }

    @Override
    public void onCashTurn(Player player, CashInContext snapshot) {
        // 환금 턴마다 호출되지만 구조적 이벤트(onCashIn/onHelperUsed/onDiscard)로 충분하므로 생략.
    }

    @Override
    public void onMessage(String message) {
        if (message != null && !message.isBlank()) {
            line("  · " + message);
        }
    }

    @Override
    public void onRoundEnd(int round) {
        line("라운드 " + round + " 종료. 코인 — " + teamA.name() + " " + teamA.coins()
                + " / " + teamB.name() + " " + teamB.coins());
        flush();
    }

    @Override
    public void onGameOver(Team winner) {
        line("");
        line("=== 게임 종료 — 승리: " + winner.name() + " ===");
        line("최종 코인 — " + teamA.name() + " " + teamA.coins() + " / " + teamB.name() + " " + teamB.coins());
        close();
    }

    // ─── 보조 ─────────────────────────────────────────────────────────────────────

    private void dumpHoldings() {
        line("  보관 상태:");
        for (Team team : List.of(teamA, teamB)) {
            for (Player p : team.members()) {
                line("    " + tag(p) + ": " + cards(p.holdings()) + " | " + team.coins() + "코인");
            }
        }
    }

    private String tag(Player player) {
        return tags.getOrDefault(player, player.name());
    }

    private static String faceDownNote(boolean hasFaceDown) {
        return hasFaceDown ? "  (+뒷면 1장)" : "";
    }

    private static String cards(List<Card> cards) {
        if (cards == null || cards.isEmpty()) {
            return "(없음)";
        }
        return cards.stream().map(Card::displayName).collect(Collectors.joining(", "));
    }

    private void line(String text) {
        if (closed) {
            return;
        }
        try {
            out.write(text);
            out.write(System.lineSeparator());
            out.flush();
        } catch (IOException e) {
            closed = true;
        }
    }

    private void flush() {
        try {
            if (!closed) {
                out.flush();
            }
        } catch (IOException ignored) {
            // best-effort
        }
    }

    private void close() {
        if (closed) {
            return;
        }
        try {
            out.flush();
            out.close();
        } catch (IOException ignored) {
            // best-effort
        } finally {
            closed = true;
        }
    }
}
