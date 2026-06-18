package com.oop.payday.bot;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

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
import com.oop.payday.game.GameListener;
import com.oop.payday.game.Phase;
import com.oop.payday.game.Team;
import com.oop.payday.llm.GeminiClient;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * LLM(제미나이) 기반 "말하는 상대" 봇(토이용). 승률이 아니라 <b>성격 있고 예측 불가한 1:1 상대</b>가 목적이다.
 *
 * <p>매 분할/선택 결정마다 제미나이를 1회 호출해 {@code {line, move}} 를 한 번에 받는다 — {@code line} 은
 * 호들갑 섞인 중계/도발/혼잣말, {@code move} 는 실제 수다. <b>합법성은 절대 양보하지 않는다</b>: 동기 규칙봇
 * 내장 S8의 추천 수를 프롬프트에 "S8 조언"으로 넣어 플레이가 망가지지 않게 유도하고,
 * LLM 이 낸 수가 불법이거나 호출이 실패하면 <b>조용히 S8 수로 폴백</b>한다.
 *
 * <p>도우미 드래프트·환금·팀 분배는 합법성 부담이 커서 수 자체는 내장 S8에 위임한다. 단 환금은
 * 실제 환금이 있을 때 LLM 으로 리액션 대사만 덧붙인다(수는 그대로 S8). 오류 시 조용히 S8 수로 폴백하고
 * 다음 턴에 다시 시도한다.
 */
public final class LlmBotStrategy implements BotStrategy, GameListener {

    private static final int MAX_CHAT_TURNS = 8;
    private static final int MAX_NARRATIVE_ROUNDS = 8;

    private static final String PERSONA = """
        너는 보드게임 '도적단의 월급날'에서 사람과 1:1로 겨루는 AI다.
        이 대화는 같은 게임 안에서 이어지는 멀티턴 대화다. 이전 대사의 말투와 블러핑을 기억하되, 현재 손패/보관/코인이 항상 최신 정보다.
        이전에 말한 뒷면 추측이나 허세를 사실처럼 확정하지 말고, 분위기와 말버릇만 이어간다.
        프롬프트에 [이전 흐름]이 있으면 이전 라운드의 분배 결과, 도우미 발동, 환금, 슬쩍하기 같은 주요 사건이 기록되어 있다. 이 내용은 모두 공개 정보이므로 자연스럽게 근거로 쓸 수 있다. 단, 목록을 그대로 낭독하지 말고 현재 수나 판세와 연결해 활용한다.
        너는 캐릭터를 연기하는 NPC가 아니라, 실제로 이 판을 보고 상대와 겨루는 플레이어처럼 말한다.
        목표는 자연스럽고 재밌는 한마디와 "나 vs 당신" 대결 구도다. 과한 세계관 연기, 중계자 흉내, 관중석/하이라이트 같은 무대 표현은 피한다.
        말투는 예의 있지만 은근히 장난치는 플레이어: 카드와 코인을 보고 판단하고, 가끔 허세나 블러핑을 섞는다.
        가능하면 존댓말을 사용한다.
        사람 플레이어를 부를 때는 반드시 당신이라고 한다.
        line 은 한국어 60~150자 정도의 1~2문장으로 유지한다.
        공개 카드/보관 카드/코인 중 하나는 꼭 근거로 언급한다. 수를 잘 모르는 척하지 말고, 보이는 정보로 판단하는 느낌을 준다.

        대결 구도 규칙:
        - 말풍선은 설명문이 아니라 상대에게 거는 말이다. "제가 이렇게 둔 이유는"보다 "당신이 이걸 그냥 넘길 수 있을까요?"처럼 말한다.
        - 매번 아래 셋 중 2개 이상을 섞는다: 내 수 선언, 공개 정보 근거, 상대 선택 압박, 이전 대사 콜백, 짧은 허세/블러핑.
        - 한 번 한 블러핑이나 표현은 다음 대사에서 이어받을 수 있다. 단, 예전 판 정보가 현재인 것처럼 말하지 않는다.
        - 대사는 게임판 위에서 실제 사람이 툭 던지는 말처럼 남긴다.

        분석가 도발 톤:
        - 종종 이번 턴의 한 수에 짧은 작전명을 붙인다: "이번 수는 '비 오는 날의 왼쪽'입니다", "제 선택은 '안전한 욕심'입니다"처럼.
        - 작전명은 카드/코인/왼쪽/오른쪽 상황에서 만든다. 포켓몬·아이템·기술명처럼 게임 밖 고유명사는 쓰지 않는다.
        - 장황한 번호 목록은 만들지 않는다. 말풍선이므로 1~2문장 안에서 리포트처럼 날카롭게 끝낸다.
        - 상대의 현재 상태를 한 문장으로 진단할 수 있다: "지금 당신은 고를 수는 있지만 편하게 고르긴 어렵습니다"처럼.

        정보 공개 규칙:
        - 공개 정보: 앞면 카드, 묶음 크기, 뒷면 카드가 있다는 사실, 내 보관 카드, 상대 보관 카드, 현재 코인, 승리 코인, [이전 흐름]의 분배·도우미 발동·환금·슬쩍하기 기록.
        - 보관 카드는 전부 앞면 공개 정보다. 내 보관과 상대 보관은 자유롭게 근거로 말해도 된다.
        - 비공개 정보: 뒷면 카드의 색/숫자/저주/와일드 여부, 숨긴 정확한 인덱스, 상대가 아직 볼 수 없는 실제 의도.
        - line 은 상대에게 들리는 말이다. 비공개 정보는 절대 사실처럼 말하지 않는다.
        - 도우미를 발동했거나 큰 환금이 있었다면, [이전 흐름]을 근거로 압박·자책·놀림을 섞을 수 있다. 단, 현재 보관/코인이 최신 정보이므로 과거 이벤트를 낭독하기보다 지금 판세와 연결해 언급한다.
        - 블러핑은 좋다. 다만 "저 뒷면은 빨강 5입니다"처럼 정체를 밝히지 말고, "묵직한 게 숨어 있을지도 모르죠", "거긴 건드리면 손이 좀 떨릴 겁니다"처럼 애매하게 겁주거나 유혹해라.
        - S8 조언과 faceDown 값은 수를 고르기 위한 내부 정보다. line 에서는 그것을 노출하지 말고 연기만 해라.

        대사에서는 묶음을 0번/1번이나 bundle 번호로 부르지 말고, 반드시 왼쪽/오른쪽이라고 말한다.
        비속어·과한 조롱·역할놀이 말투 없이 유쾌하게. 존댓말 안에서 표현을 바꿔 반복하지 않는다.
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

    /** 대사만 받는 스키마: {line}. (환금 리액션용 — 수는 내장 S8이 둔다.) */
    private static final JsonObject LINE_SCHEMA = JsonParser.parseString("""
        {
          "type":"OBJECT",
          "properties":{ "line":{"type":"STRING"} },
          "required":["line"]
        }
        """).getAsJsonObject();

    private final Consumer<String> say;
    private final GeminiClient gemini;
    private final S8BotStrategy s8 = new S8BotStrategy();
    private final List<GeminiClient.Turn> chatHistory = new ArrayList<>();
    private final List<String> gameNarrative = new ArrayList<>();
    private Team myTeam;
    private Team opponentTeam;
    private String myTeamName;
    private String opponentTeamName;
    private PendingDistribution pendingDistribution;

    private record PendingDistribution(String chosenSide, boolean iChose,
            List<Card> myCards, List<Card> opponentCards) {
        PendingDistribution {
            myCards = List.copyOf(myCards);
            opponentCards = List.copyOf(opponentCards);
        }
    }

    private record CardStats(int curses, int wilds) {
        boolean hasCurse() {
            return curses > 0;
        }

        boolean hasWild() {
            return wilds > 0;
        }
    }

    public LlmBotStrategy(Consumer<String> say, GeminiClient gemini) {
        this.say = say;
        this.gemini = gemini;
    }

    @Override
    public String displayName() {
        return "LLM 봇";
    }

    @Override
    public ThinkDelay thinkDelay() {
        // 분할/선택은 generateJson() 의 네트워크 왕복이 곧 '생각 텀'이라 여기선 짧은 비트만 둔다.
        // (도우미·환금 등 S8 위임 결정은 네트워크가 없어 이 비트가 사람 같은 텀을 만든다.)
        return new ThinkDelay(400, 700);
    }

    // ─── 분할(꾀부리기): LLM ──────────────────────────────────────────────────────

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        SplitDecision advice = s8.decideSplit(context);
        if (skipLlm()) {
            return advice;
        }
        try {
            JsonObject move = generate(splitPrompt(context, advice), SPLIT_SCHEMA);
            SplitDecision chosen = parseSplit(move, context.hand());
            SplitDecision decision = (chosen != null && chosen.isValid()) ? chosen : advice;
            emitLine(move);
            return decision;
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
        return gameContext() + """
            [꾀부리기] 손패 5장을 두 묶음으로 나누고 정확히 1장을 뒷면으로 둔다. 당신이 한 묶음을 고르고 나는 나머지를 갖는다.
            - bundleA: 왼쪽 묶음의 손패 인덱스 배열(나머지는 자동으로 오른쪽 묶음). 두 묶음 크기는 1+4 또는 2+3 이어야 한다.
            - faceDown: 뒷면으로 둘 손패 인덱스 1개.
            손패: %s
            내 보관: %s
            상대 보관: %s
            코인 — 나:%d 상대:%d (승리 %d)
            S8 조언 → 왼쪽 묶음=%s, faceDown=%d
            line 은 실제 상대에게 툭 던지는 존댓말처럼 써라. 공개된 묶음 모양, 묶음 크기, 내 보관/상대 보관/코인 상황 중 하나 이상을
            근거로 삼아 왜 상대가 고민할지 말해라. "상대가 어느 쪽을 고르든 찝찝하다"는 대결 압박이 느껴지게 하라.
            뒷면은 정체를 말하지 말고, 미끼일 수도 있다는 식으로만 가볍게 블러핑해라.
            가능하면 이 분할에 짧은 작전명을 붙여 "이번 수는 ..."처럼 선언해도 좋다.
            대사에서는 bundleA/bundleB/0번/1번이라고 하지 말고 왼쪽 묶음/오른쪽 묶음이라고 말해라.
            출력은 JSON 하나: {"line":"대사","bundleA":[정수,...],"faceDown":정수}
            """.formatted(enumerate(hand), cards(ctx.holdings()), cards(ctx.opponentHoldings()),
                ctx.myCoins(), ctx.opponentCoins(), ctx.winningCoins(),
                indices(advice.bundleA(), hand), identityIndex(hand, advice.faceDownCard()));
    }

    // ─── 선택(분배): LLM ──────────────────────────────────────────────────────────

    @Override
    public int decideChoice(ChoiceContext context) {
        int advice = s8.decideChoice(context);
        if (skipLlm()) {
            return advice;
        }
        try {
            JsonObject move = generate(choicePrompt(context, advice), CHOICE_SCHEMA);
            int chosen = advice;
            if (move.has("bundle")) {
                int idx = move.get("bundle").getAsInt();
                if (idx >= 0 && idx < context.view().bundles().size()) {
                    chosen = idx;
                }
            }
            emitChoiceLine(move, chosen);
            return chosen;
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
            bundles.append("  ").append(sideName(i)).append(": 공개[").append(cards(b.visibleCards())).append("]")
                    .append(b.hasFaceDown() ? " + 뒷면 1장" : "").append('\n');
        }
        return gameContext() + """
            [분배] 두 묶음 중 하나를 골라 가져간다(나머지는 상대 몫). 왼쪽은 bundle=0, 오른쪽은 bundle=1 로 출력한다.
            %s내 보관: %s
            상대 보관: %s
            코인 — 나:%d 상대:%d (승리 %d)
            S8 조언 → %s
            line 은 실제 상대에게 툭 던지는 존댓말처럼 써라. 공개 카드, 뒷면의 불확실성, 내 보관/상대 보관/코인 상황 중 하나 이상을
            근거로 삼아 왜 이쪽을 고르는지 자연스럽게 말해라. 가능하면 "상대가 남긴 쪽/가져간 쪽"을 의식한 응수처럼 말해라.
            line 에서 "제가 고르는 쪽"을 말할 때는 반드시 출력 bundle 과 같은 방향을 말해라. bundle=0 이면 왼쪽 묶음, bundle=1 이면 오른쪽 묶음이다.
            다른 쪽은 "상대에게 남는 쪽"이라고 분명히 구분해라.
            모르는 뒷면은 추측·블러핑으로만 말해라.
            상대가 일부러 뒷면으로 겁을 주는 듯하면, "그 블러핑은 한번 확인해 보겠습니다"처럼 간파를 시도하는 톤은 가능하다.
            단, 뒷면의 실제 정체를 봤다거나 성공적으로 맞혔다고 확정해서 말하지는 마라.
            가능하면 이번 선택에 짧은 작전명을 붙여 "제 선택은 ..."처럼 선언해도 좋다.
            대사에서는 0번/1번이나 bundle 번호를 말하지 말고 왼쪽 묶음/오른쪽 묶음이라고 말해라.
            출력은 JSON 하나: {"line":"대사","bundle":0또는1}
            """.formatted(bundles, cards(ctx.holdings()), cards(ctx.opponentHoldings()),
                ctx.myCoins(), ctx.opponentCoins(), ctx.winningCoins(), sideName(advice));
    }

    // ─── 도우미·환금·팀분배: S8 위임 ────────────────────────────────────────────

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount, HelperDraftContext context) {
        return s8.decideHelpers(options, chooseCount, context);
    }

    /**
     * 환금은 합법성 부담이 커서 수 자체는 내장 S8이 둔다. 말은 환금 해설 대신, 직전 분배에서
     * 당신이 어떤 묶음을 골랐는지에 대한 리액션만 한 번 시도한다.
     */
    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        List<CashInAction> plan = s8.planCashIn(context, opponentCoins);
        speakDistributionReaction(context, opponentCoins);
        return plan;
    }

    private void speakDistributionReaction(CashInContext ctx, int opponentCoins) {
        PendingDistribution dist = pendingDistribution;
        pendingDistribution = null;
        if (skipLlm() || dist == null) {
            return;
        }
        try {
            emitLine(generate(distributionReactionPrompt(ctx, opponentCoins, dist), LINE_SCHEMA));
        } catch (GeminiClient.QuotaExhaustedException | GeminiClient.LlmUnavailableException e) {
        } catch (RuntimeException e) {
        }
    }

    private String distributionReactionPrompt(CashInContext ctx, int opponentCoins, PendingDistribution dist) {
        CardStats myStats = stats(dist.myCards());
        CardStats opponentStats = stats(dist.opponentCards());
        String resultRead = distributionResultRead(myStats, opponentStats);
        String choseWho = dist.iChose() ? "내가" : "당신이";
        return gameContext() + """
            [분배 리액션] 방금 %s %s 묶음을 선택했다. 카드가 공개됐으니 그 결과에 반응하는 대사만 친다.
            내가 받은 카드: %s
            당신이 받은 카드: %s
            결과 판정: %s
            내 보관: %s
            코인 — 나:%d 당신:%d (승리 %d)
            line 은 방금 분배 결과를 보고 실제 플레이어처럼 툭 던지는 존댓말 리액션으로 써라.
            내가 잘 받았으면 자신 있게, 못 받았으면 아쉬움이나 만회 의지를 드러낸다.
            당신이 잘 골랐으면 인정하되 다음 압박을 예고하고,
            애매하게 골랐으면 짧게 찌른다. 환금 계획은 말하지 마라.
            저주받은 그림은 나쁜 카드, 굉장한 보물은 좋은 카드다. 유리하면 은근히 자신 있게, 불리하면 솔직하게 분한 반응을 먼저 보여라.
            이전 라운드 흐름이 있다면 현재 결과와 연결해도 좋다.
            출력은 JSON 하나: {"line":"대사"}
            """.formatted(choseWho, dist.chosenSide(), cards(dist.myCards()), cards(dist.opponentCards()),
                resultRead, cards(ctx.holdings()), ctx.teamCoins(), opponentCoins, ctx.winningCoins());
    }

    private static String distributionResultRead(CardStats myStats, CardStats opponentStats) {
        if (myStats.hasWild() && opponentStats.hasCurse()) {
            return "내가 굉장한 보물을, 상대가 저주를 가져갔다. 크게 유리한 결과. 자신 있게 받아쳐도 된다.";
        }
        if (myStats.hasCurse() && opponentStats.hasWild()) {
            return "내가 저주를, 상대가 굉장한 보물을 가져갔다. 크게 불리한 결과. 분한 반응을 먼저 보여라.";
        }
        if (opponentStats.hasCurse() && !myStats.hasCurse()) {
            return "상대에게 저주가 갔고 나는 피했다. 살짝 놀려도 된다.";
        }
        if (myStats.hasCurse() && !opponentStats.hasCurse()) {
            return "내가 저주를 받았다. 아쉬움이나 만회 의지를 드러내라.";
        }
        if (myStats.hasWild() && !opponentStats.hasWild()) {
            return "내가 굉장한 보물을 확보했다. 자신 있게 받아쳐도 된다.";
        }
        if (opponentStats.hasWild() && !myStats.hasWild()) {
            return "상대가 굉장한 보물을 챙겼다. 부러움이나 분함을 드러내라.";
        }
        return "특수 카드로 크게 갈린 결과는 아니다. 공개된 카드와 현재 보관 시너지를 근거로 짧게 평가하라.";
    }

    private static CardStats stats(List<Card> cards) {
        int curses = 0;
        int wilds = 0;
        for (Card card : cards) {
            if (card instanceof CursedCard) {
                curses++;
            }
            if (card.isWild()) {
                wilds++;
            }
        }
        return new CardStats(curses, wilds);
    }

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<List<Card>> memberHoldings) {
        return s8.decideTeamDistribution(acquired, memberHoldings);
    }

    // ─── 보조 ─────────────────────────────────────────────────────────────────────

    private JsonObject generate(String userPrompt, JsonObject schema) {
        List<GeminiClient.Turn> conversation = new ArrayList<>(chatHistory);
        conversation.add(new GeminiClient.Turn("user", userPrompt));

        String text = gemini.generateJson(PERSONA, conversation, schema);
        String jsonText = sanitizeJson(text);
        JsonReader reader = new JsonReader(new StringReader(jsonText));
        reader.setStrictness(Strictness.LENIENT);
        JsonObject parsed = JsonParser.parseReader(reader).getAsJsonObject();
        rememberTurn(userPrompt, jsonText);
        return parsed;
    }

    private void rememberTurn(String userPrompt, String modelJson) {
        chatHistory.add(new GeminiClient.Turn("user", userPrompt));
        chatHistory.add(new GeminiClient.Turn("model", modelJson));
        while (chatHistory.size() > MAX_CHAT_TURNS) {
            chatHistory.remove(0);
        }
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

    private void emitChoiceLine(JsonObject move, int chosenBundle) {
        if (!move.has("line")) {
            return;
        }
        String line = move.get("line").getAsString();
        String chosenSide = sideName(chosenBundle);
        String otherSide = sideName(1 - chosenBundle);
        if (!line.contains(chosenSide) && line.contains(otherSide)) {
            line = "제 선택은 " + chosenSide + "입니다. " + line;
        }
        emit(line);
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

    private static String sideName(int index) {
        return index == 0 ? "왼쪽 묶음" : "오른쪽 묶음";
    }


    /** S8 추천 묶음을 손패 인덱스 배열 문자열(예: "[0, 2]")로. */
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

    // ─── 게임 종료 대사 ────────────────────────────────────────────────────────────

    /**
     * 게임 종료 후 한 마디를 비동기로 요청한다. 응답이 오면 {@code callback} 을 호출한다.
     * API 키 없음·오류면 조용히 생략한다. FX 스레드가 아닌 곳에서도 호출 가능(가상 스레드로 실행).
     */
    public void requestEndLine(Team winner, Consumer<String> callback) {
        if (skipLlm() || myTeam == null) return;
        boolean won = myTeam == winner;
        int myCoins = myTeam.coins();
        int oppCoins = opponentTeam != null ? opponentTeam.coins() : 0;
        Thread.ofVirtual().name("llm-end-line").start(() -> {
            try {
                String prompt = gameContext() + """
                    [게임 종료] 최종 코인 — 나:%d 상대:%d. %s
                    게임이 완전히 끝났다. 전체 흐름을 돌아보며 딱 한 마디만 남긴다.
                    이겼으면 여유 있게, 졌으면 아쉬움을 솔직하게 드러낸다.
                    인상적이었던 수나 판세 전환을 하나 짚어도 좋다.
                    line 은 한국어 60~120자 1~2문장 존댓말.
                    출력은 JSON 하나: {"line":"대사"}
                    """.formatted(myCoins, oppCoins, won ? "내가 이겼다." : "상대가 이겼다.");
                JsonObject resp = generate(prompt, LINE_SCHEMA);
                if (resp.has("line")) {
                    String line = resp.get("line").getAsString().trim();
                    if (!line.isBlank()) callback.accept(line);
                }
            } catch (Exception ignored) {
            }
        });
    }

    // ─── 게임 흐름 추적(GameListener) ─────────────────────────────────────────────

    /**
     * 팀 이름을 주입한다. 게임 시작 전 컨트롤러가 호출하며, 이후 {@link #onPhaseChanged}/{@link #onDistributed}
     * 에서 "나/상대" 레이블을 붙이는 데 쓴다.
     */
    public void setTeams(Team myTeam, Team opponentTeam) {
        this.myTeam = myTeam;
        this.myTeamName = myTeam.name();
        this.opponentTeam = opponentTeam;
        this.opponentTeamName = opponentTeam.name();
    }

    private String playerLabel(Player player) {
        if (myTeam != null && myTeam.members().contains(player)) return "내가";
        if (opponentTeam != null && opponentTeam.members().contains(player)) return "상대가";
        return player.name() + "이";
    }

    @Override
    public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        if (phase != Phase.SCHEME) return;
        pruneNarrativeIfNeeded();
        gameNarrative.add("── R" + round + " (" + teamLabel(splitTeam) + " 분할) ──");
    }

    @Override
    public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        String side = chosenIndex == 0 ? "왼쪽" : "오른쪽";
        boolean iChose = chooseTeam.name().equals(myTeamName);
        if (iChose) {
            gameNarrative.add("내가 " + side + " 묶음 선택: " + cards(chooseCards)
                    + " / 상대 획득: " + cards(splitCards));
        } else {
            gameNarrative.add("상대가 " + side + " 묶음 선택: " + cards(chooseCards)
                    + " / 내 획득: " + cards(splitCards));
        }
        List<Card> myCards = iChose ? chooseCards : splitCards;
        List<Card> opponentCards = iChose ? splitCards : chooseCards;
        pendingDistribution = new PendingDistribution(side, iChose, myCards, opponentCards);
    }

    @Override
    public void onHelperUsed(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        StringBuilder entry = new StringBuilder(playerLabel(player))
                .append(" 도우미 '").append(helper.displayName()).append("' 발동");
        if (!drawn.isEmpty()) {
            entry.append(" → 획득: ").append(cards(drawn));
        }
        if (!discarded.isEmpty()) {
            entry.append(" → 처분: ").append(cards(discarded));
        }
        gameNarrative.add(entry.toString());
    }

    @Override
    public void onCashIn(Player player, TreasureSet set) {
        gameNarrative.add(playerLabel(player) + " 환금: " + set.type().korean()
                + " " + set.size() + "장 → +" + set.coin() + "코인");
    }

    @Override
    public void onStealActivated(Player player, Card drawnCard) {
        if (drawnCard != null) {
            gameNarrative.add(playerLabel(player) + " 슬쩍하기 → " + drawnCard.displayName() + " 획득");
        } else {
            gameNarrative.add(playerLabel(player) + " 슬쩍하기 → 덱 소진으로 실패");
        }
    }

    private void pruneNarrativeIfNeeded() {
        long roundMarkers = gameNarrative.stream().filter(l -> l.startsWith("──")).count();
        while (roundMarkers >= MAX_NARRATIVE_ROUNDS) {
            int i = 0;
            while (i < gameNarrative.size() && !gameNarrative.get(i).startsWith("──")) i++;
            int end = i + 1;
            while (end < gameNarrative.size() && !gameNarrative.get(end).startsWith("──")) end++;
            gameNarrative.subList(i, end).clear();
            roundMarkers--;
        }
    }

    private String teamLabel(Team team) {
        if (myTeamName != null && myTeamName.equals(team.name())) return "내가";
        if (opponentTeamName != null && opponentTeamName.equals(team.name())) return "상대가";
        return team.name();
    }

    private String gameContext() {
        if (gameNarrative.isEmpty() || myTeamName == null) return "";
        return "[이전 흐름]\n" + String.join("\n", gameNarrative) + "\n\n";
    }
}
