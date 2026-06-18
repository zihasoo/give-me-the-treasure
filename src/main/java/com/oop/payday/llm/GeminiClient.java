package com.oop.payday.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 제미나이 API(generativelanguage) 텍스트 생성 1회 호출 클라이언트. LLM 봇 전용 — 게임 엔진과 무관하다.
 *
 * <p><b>모델 강등 사다리</b>({@link #MODELS}): 위에서부터 쓰다가 일일 한도(HTTP 429 / RESOURCE_EXHAUSTED)에
 * 걸리면 다음 모델로 내려가 재시도하고, 내려간 티어로 고정한다({@link #tier}). 마지막 모델까지 한도면
 * {@link QuotaExhaustedException}, 그 외 오류(네트워크·키오류·파싱)는 {@link LlmUnavailableException} 을
 * 던져 호출자가 규칙봇으로 폴백하게 한다.
 */
public final class GeminiClient {

    /** 강등 순서대로의 모델 ID. 앞에서부터 시도한다. */
    private static final String[] MODELS = {
            "gemini-3.1-flash-lite",  // Gemini 3.1 Flash Lite (1순위)
            "gemini-3.5-flash",       // Gemini 3.5 Flash
    };

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private final String apiKey;
    private final HttpClient http;
    /** 현재 사용 중인 {@link #MODELS} 인덱스(세션 단위로만 내려가고 올라가지 않는다). */
    private volatile int tier;

    /** Gemini contents 에 들어갈 대화 턴. {@code role} 은 {@code user} 또는 {@code model}. */
    public record Turn(String role, String text) {
    }

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    public boolean hasKey() {
        return !apiKey.isEmpty();
    }

    /**
     * 시스템/유저 프롬프트로 텍스트 1회 생성. 모델이 낸 본문 텍스트를 그대로 반환한다(호출자가 JSON 파싱).
     * {@code responseSchema} 는 Gemini 계열에서만 구조화 출력 제약으로 쓰이고 Gemma 에서는 무시된다.
     *
     * @throws QuotaExhaustedException 모든 모델이 일일 한도 초과
     * @throws LlmUnavailableException 키없음·네트워크·키오류·파싱 실패 등
     */
    public String generateJson(String systemPrompt, String userPrompt, JsonObject responseSchema) {
        return generateJson(systemPrompt, List.of(new Turn("user", userPrompt)), responseSchema);
    }

    /**
     * 시스템 프롬프트와 최근 대화 턴으로 텍스트 1회 생성. 마지막 턴은 보통 이번 user 프롬프트다.
     */
    public String generateJson(String systemPrompt, List<Turn> conversation, JsonObject responseSchema) {
        if (apiKey.isEmpty()) {
            throw new LlmUnavailableException("API 키가 비어 있음");
        }
        for (int i = tier; i < MODELS.length; i++) {
            try {
                String text = call(MODELS[i], systemPrompt, conversation, responseSchema);
                tier = i; // 성공한 티어로 고정(이미 소진된 상위 티어로 되돌아가지 않는다).
                return text;
            } catch (QuotaExhaustedException e) {
                tier = Math.min(i + 1, MODELS.length - 1); // 다음 모델로 내려가 계속 시도.
            }
        }
        throw new QuotaExhaustedException("모든 모델 사용 불가(일일 한도/미존재)");
    }

    private String call(String model, String systemPrompt, List<Turn> conversation, JsonObject responseSchema) {
        JsonObject body = buildBody(systemPrompt, conversation, responseSchema);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ENDPOINT.formatted(model)))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.http.HttpTimeoutException e) {
            throw new LlmTimeoutException("타임아웃: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new LlmUnavailableException("네트워크 오류: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("호출 중단됨", e);
        }

        int status = response.statusCode();
        if (status == 429 || status == 404) {
            // 429=일일 한도, 404=모델 ID 미존재 → 둘 다 이 모델로는 더 못 받으니 다음 모델로 강등한다.
            // (404 강등 덕에 MODELS 의 추정 ID 가 틀려도 다음 모델로 자동으로 넘어간다.)
            throw new QuotaExhaustedException(model + (status == 429 ? " 일일 한도 초과(429)" : " 모델 없음(404)"));
        }
        if (status / 100 != 2) {
            throw new LlmUnavailableException("HTTP " + status + " (" + model + "): " + truncate(response.body()));
        }
        return extractText(response.body());
    }

    private static JsonObject buildBody(String systemPrompt, List<Turn> conversation, JsonObject responseSchema) {
        JsonObject body = new JsonObject();

        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", partsOf(systemPrompt));
        body.add("system_instruction", systemInstruction);

        body.add("contents", contentsOf(conversation));

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", responseSchema);
        generationConfig.addProperty("temperature", 1.1);
        body.add("generationConfig", generationConfig);

        return body;
    }

    private static JsonArray contentsOf(List<Turn> conversation) {
        JsonArray contents = new JsonArray();
        for (Turn turn : conversation) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            JsonObject content = new JsonObject();
            content.addProperty("role", "model".equals(turn.role()) ? "model" : "user");
            content.add("parts", partsOf(turn.text()));
            contents.add(content);
        }
        return contents;
    }

    private static JsonArray partsOf(String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        return parts;
    }

    private static String extractText(String responseBody) {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                throw new LlmUnavailableException("응답에 candidates 없음");
            }
            JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
            JsonArray parts = content == null ? null : content.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) {
                throw new LlmUnavailableException("응답에 parts 없음(차단/토큰초과 가능)");
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : parts) {
                JsonObject partObj = part.getAsJsonObject();
                if (partObj.has("text")) {
                    sb.append(partObj.get("text").getAsString());
                }
            }
            return sb.toString();
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("응답 파싱 실패: " + e.getMessage(), e);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    /** 모든 모델이 사용 불가(전부 일일 한도 429 또는 미존재 404). 호출자는 규칙봇으로 폴백한다. */
    public static final class QuotaExhaustedException extends RuntimeException {
        public QuotaExhaustedException(String message) {
            super(message);
        }
    }

    /** 요청 타임아웃. 일시적 지연이므로 호출자는 LLM 을 끄지 않고 규칙봇으로만 폴백한다. */
    public static final class LlmTimeoutException extends LlmUnavailableException {
        public LlmTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 네트워크 불가·키 오류·응답 파싱 실패 등 일시/영구 비가용. 호출자는 규칙봇으로 폴백한다. */
    public static class LlmUnavailableException extends RuntimeException {
        public LlmUnavailableException(String message) {
            super(message);
        }

        public LlmUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
