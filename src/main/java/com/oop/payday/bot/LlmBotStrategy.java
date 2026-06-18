package com.oop.payday.bot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

import java.io.StringReader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.HelperDraftContext;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.llm.GeminiClient;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * LLM(제미나이) 기반 "말하는 상대" 봇(토이용). 승률이 아니라 <b>성격 있고 예측 불가한 1:1 상대</b>가 목적이다.
 *
 * <p>매 분할/선택 결정마다 제미나이를 1회 호출해 {@code {line, move}} 를 한 번에 받는다 — {@code line} 은
 * 무뚝뚝한 츤데레 도발/혼잣말, {@code move} 는 실제 수다. <b>합법성은 절대 양보하지 않는다</b>: 동기 규칙봇
 * ({@code advisor}, 기본 S8)의 추천 수를 프롬프트에 "S8 조언"으로 넣어 플레이가 망가지지 않게 유도하고,
 * LLM 이 낸 수가 불법이거나 호출이 실패하면 <b>조용히 advisor 수로 폴백</b>한다.
 *
 * <p>도우미 드래프트·환금·팀 분배는 합법성 부담이 커서 수 자체는 {@code advisor} 에 위임한다. 단 환금은
 * 실제 환금이 있을 때 LLM 으로 리액션 대사만 덧붙인다(수는 그대로 S8). 오류 시 조용히 advisor 수로 폴백하고
 * 다음 턴에 다시 시도한다.
 */
public final class LlmBotStrategy implements BotStrategy {

    private static final String PERSONA = """
        너는 보드게임 '도적단의 월급날'에서 사람과 1:1로 겨루는 AI다.
        포켓몬 배틀 중계하듯 — 유쾌하고 자연스럽게, 지금 왜 이 수를 두는지 가볍게 설명하면서 상황에 반응한다.
        예: "이 묶음에 고가 카드가 몰려 있으니 내가 가져가야지", "오 이건 좀 애매한데, 일단 이쪽으로 가볼게"
        한국어로, 100자 이내. 따옴표·이모지 없이. 전략 설명·감탄·상대 반응을 자유롭게 섞어도 좋다.
        반드시 JSON 객체 하나로만 답한다. 코드펜스(```)·설명·여분 텍스트 금지.
        """;

    // 제미나이 responseSchema 는 Schema 프로토에 매핑되며 type 은 REST JSON 에서 대문자 enum 으로 직렬화된다.
    /** 분할 응답 스키마: {line, bundleA(손패 인덱스 배열), faceDown(손패 인덱스)}. */
    private static final JsonObject SPLIT_SCHEMA = JsonParser.parseString("""
        {
          "type":"OBJECT",
          "properties":{
            "line":{"type":"STRING"},
            "bundleA":{"type":"ARRAY","items":{"type":"INTEGER"}},
            "faceDown":{"type":"INTEGER"}
          },
          "required":["line","bundleA","faceDown"],
          "propertyOrdering":["line","bundleA","faceDown"]
        }
        """).getAsJsonObject();

    /** 선택 응답 스키마: {line, bundle(0 또는 1)}. */
    private static final JsonObject CHOICE_SCHEMA = JsonParser.parseString("""
        {
          "type":"OBJECT",
          "properties":{
            "line":{"type":"STRING"},
            "bundle":{"type":"INTEGER"}
          },
          "required":["line","bundle"],
          "propertyOrdering":["line","bundle"]
        }
        """).getAsJsonObject();

    /** 대사만 받는 스키마: {line}. (환금 리액션용 — 수는 advisor 가 둔다.) */
    private static final JsonObject LINE_SCHEMA = JsonParser.parseString("""
        {
          "type":"OBJECT",
          "properties":{ "line":{"type":"STRING"} },
          "required":["line"]
        }
        """).getAsJsonObject();

    private final Consumer<String> say;
    private final GeminiClient gemini;
    private final BotStrategy advisor;

    public LlmBotStrategy(Consumer<String> say, GeminiClient gemini, BotStrategy advisor) {
        this.say = say;
        this.gemini = gemini;
        this.advisor = advisor;
    }

    @Override
    public String displayName() {
        return "토이 봇";
    }

    @Override
    public void think(boolean paced) {
        if (!paced) {
            return;
        }
        // 분할/선택은 generateJson() 의 네트워크 왕복이 곧 '생각 텀'이라 여기선 짧은 비트만 둔다.
        // (도우미·환금 등 advisor 위임 결정은 네트워크가 없어 이 비트가 사람 같은 텀을 만든다.)
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(600, 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ─── 분할(꾀부리기): LLM ──────────────────────────────────────────────────────

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        SplitDecision advice = advisor.decideSplit(context);
        if (skipLlm()) {
            return advice;
        }
        try {
            JsonObject move = generate(splitPrompt(context, advice), SPLIT_SCHEMA);
            emitLine(move);
            SplitDecision chosen = parseSplit(move, context.hand());
            return (chosen != null && chosen.isValid()) ? chosen : advice;
        } catch (GeminiClient.QuotaExhaustedException | GeminiClient.LlmUnavailableException e) {
            return advice;
        } catch (RuntimeException e) {
            return advice;
        }
    }

    private SplitDecision parseSplit(JsonObject move, List<Card> hand) {
        if (!move.has("bundleA") || !move.has("faceDown")) {
            return null;
        }
        int n = hand.size();
        boolean[] inA = new boolean[n];
        List<Card> bundleA = new ArrayList<>();
        for (JsonElement e : move.getAsJsonArray("bundleA")) {
            int idx = e.getAsInt();
            if (idx < 0 || idx >= n || inA[idx]) {
                return null; // 범위 밖/중복 인덱스 → 폴백
            }
            inA[idx] = true;
            bundleA.add(hand.get(idx));
        }
        List<Card> bundleB = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!inA[i]) {
                bundleB.add(hand.get(i));
            }
        }
        int faceIdx = move.get("faceDown").getAsInt();
        if (faceIdx < 0 || faceIdx >= n) {
            return null;
        }
        return new SplitDecision(bundleA, bundleB, hand.get(faceIdx));
    }

    private String splitPrompt(SplitContext ctx, SplitDecision advice) {
        List<Card> hand = ctx.hand();
        return """
            [꾀부리기] 손패 5장을 두 묶음으로 나누고 정확히 1장을 뒷면으로 둔다. 상대가 한 묶음을 고르고 너는 나머지를 갖는다.
            - bundleA: 손패 인덱스 배열(나머지는 자동으로 bundleB). 두 묶음 크기는 1+4 또는 2+3 이어야 한다.
            - faceDown: 뒷면으로 둘 손패 인덱스 1개.
            손패: %s
            내 보관: %s
            상대 보관(앞면 공개): %s
            코인 — 나:%d 상대:%d (승리 %d)
            S8 조언 → bundleA=%s, faceDown=%d
            왜 이렇게 나눴는지 가볍게 설명하거나 상황에 반응하는 line 한 마디를 써라.
            출력은 JSON 하나: {"line":"대사","bundleA":[정수,...],"faceDown":정수}
            """.formatted(enumerate(hand), cards(ctx.holdings()), cards(ctx.opponentHoldings()),
                ctx.myCoins(), ctx.opponentCoins(), ctx.winningCoins(),
                indices(advice.bundleA(), hand), identityIndex(hand, advice.faceDownCard()));
    }

    // ─── 선택(분배): LLM ──────────────────────────────────────────────────────────

    @Override
    public int decideChoice(ChoiceContext context) {
        int advice = advisor.decideChoice(context);
        if (skipLlm()) {
            return advice;
        }
        try {
            JsonObject move = generate(choicePrompt(context, advice), CHOICE_SCHEMA);
            emitLine(move);
            if (move.has("bundle")) {
                int idx = move.get("bundle").getAsInt();
                if (idx >= 0 && idx < context.view().bundles().size()) {
                    return idx;
                }
            }
            return advice;
        } catch (GeminiClient.QuotaExhaustedException | GeminiClient.LlmUnavailableException e) {
            return advice;
        } catch (RuntimeException e) {
            return advice;
        }
    }

    private String choicePrompt(ChoiceContext ctx, int advice) {
        ChoiceView view = ctx.view();
        StringBuilder bundles = new StringBuilder();
        for (int i = 0; i < view.bundles().size(); i++) {
            BundleView b = view.bundle(i);
            bundles.append("  ").append(i).append("번: 공개[").append(cards(b.visibleCards())).append("]")
                    .append(b.hasFaceDown() ? " + 뒷면 1장" : "").append('\n');
        }
        return """
            [분배] 두 묶음 중 하나를 골라 가져간다(나머지는 상대 몫). bundle 에 가져갈 묶음 번호.
            %s내 보관: %s
            상대 보관(앞면 공개): %s
            코인 — 나:%d 상대:%d (승리 %d)
            S8 조언 → bundle=%d
            왜 이 묶음을 골랐는지 가볍게 설명하거나 상황에 반응하는 line 한 마디를 써라.
            출력은 JSON 하나: {"line":"대사","bundle":0또는1}
            """.formatted(bundles, cards(ctx.holdings()), cards(ctx.opponentHoldings()),
                ctx.myCoins(), ctx.opponentCoins(), ctx.winningCoins(), advice);
    }

    // ─── 도우미·환금·팀분배: advisor(S8) 위임 ────────────────────────────────────

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount, HelperDraftContext context) {
        return advisor.decideHelpers(options, chooseCount, context);
    }

    /**
     * 환금은 합법성 부담이 커서 수 자체는 {@code advisor}(S8)가 둔다 — 다만 실제 환금이 있으면 LLM 으로
     * 그 순간의 <b>리액션 대사</b>는 친다. (이 메서드는 봇 전용 가상 스레드에서 호출되므로 블로킹 호출 OK.)
     */
    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        List<CashInAction> plan = advisor.planCashIn(context, opponentCoins);
        speakCashIn(context, opponentCoins, plan);
        return plan;
    }

    private void speakCashIn(CashInContext ctx, int opponentCoins, List<CashInAction> plan) {
        if (skipLlm() || !hasCashAction(plan)) {
            return; // LLM 꺼져 있거나(전 게임 무음) 실제 환금이 없으면 침묵.
        }
        try {
            emitLine(generate(cashPrompt(ctx, opponentCoins), LINE_SCHEMA));
        } catch (GeminiClient.QuotaExhaustedException | GeminiClient.LlmUnavailableException e) {
        } catch (RuntimeException e) {
        }
    }

    private String cashPrompt(CashInContext ctx, int opponentCoins) {
        return """
            [환금] 이제 보관 패로 세트를 만들어 코인으로 바꾼다. 수는 이미 정해졌다 — 너는 대사만 친다.
            내 보관: %s
            코인 — 나:%d 상대:%d (승리 %d)
            환금 상황을 보고 line 한 마디 — 얼마나 벌었는지, 앞으로 어떻게 될 것 같은지 자연스럽게.
            출력은 JSON 하나: {"line":"대사"}
            """.formatted(cards(ctx.holdings()), ctx.teamCoins(), opponentCoins, ctx.winningCoins());
    }

    private static boolean hasCashAction(List<CashInAction> plan) {
        for (CashInAction action : plan) {
            if (action instanceof CashInAction.Cash || action instanceof CashInAction.CashWithHelpers) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<List<Card>> memberHoldings) {
        return advisor.decideTeamDistribution(acquired, memberHoldings);
    }

    // ─── 보조 ─────────────────────────────────────────────────────────────────────

    private JsonObject generate(String userPrompt, JsonObject schema) {
        String text = gemini.generateJson(PERSONA, userPrompt, schema);
        JsonReader reader = new JsonReader(new StringReader(sanitizeJson(text)));
        reader.setStrictness(Strictness.LENIENT);
        return JsonParser.parseReader(reader).getAsJsonObject();
    }

    /**
     * 모델 출력에서 JSON 객체만 추려낸다. Gemma 처럼 구조화 출력을 강제 못 하는 모델이 코드펜스(```json …)나
     * 군더더기 텍스트로 감쌀 수 있어, 처음 '{' 부터 마지막 '}' 까지만 취한다(이미 깔끔한 JSON 이면 그대로).
     */
    private static String sanitizeJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            return text; // 브레이스가 없으면 그대로 — 파싱이 실패하면 호출부가 폴백한다.
        }
        return text.substring(start, end + 1);
    }

    private boolean skipLlm() {
        return !gemini.hasKey();
    }

    private void emitLine(JsonObject move) {
        if (move.has("line")) {
            emit(move.get("line").getAsString());
        }
    }

    private void emit(String line) {
        if (say != null && line != null && !line.isBlank()) {
            say.accept(line.trim());
        }
    }

    /** "0:노랑 5, 1:빨강 3, …" — 인덱스 붙은 손패 표기(수 직렬화 기준). */
    private static String enumerate(List<Card> cards) {
        if (cards.isEmpty()) {
            return "(없음)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(i).append(':').append(cards.get(i).displayName());
        }
        return sb.toString();
    }

    /** 인덱스 없는 카드 나열(보관/공개용). */
    private static String cards(List<Card> cards) {
        if (cards.isEmpty()) {
            return "(없음)";
        }
        return cards.stream().map(Card::displayName).reduce((a, b) -> a + ", " + b).orElse("(없음)");
    }

    /** advisor 추천 묶음을 손패 인덱스 배열 문자열(예: "[0, 2]")로. */
    private static String indices(List<Card> sub, List<Card> hand) {
        List<Integer> idx = new ArrayList<>();
        for (Card c : sub) {
            idx.add(identityIndex(hand, c));
        }
        return idx.toString();
    }

    /** 참조 동일성 기준 손패 인덱스(같은 표기 카드라도 정확히 구분). */
    private static int identityIndex(List<Card> hand, Card card) {
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i) == card) {
                return i;
            }
        }
        return -1;
    }
}
