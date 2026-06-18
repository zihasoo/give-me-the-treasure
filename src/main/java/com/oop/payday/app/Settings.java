package com.oop.payday.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 클라이언트 로컬 설정. 제미나이 API 키를 <b>게임 실행 폴더(작업 디렉터리)의 텍스트 파일</b>에 평문으로
 * 저장한다 — 한 번 입력하면 재실행해도 유지된다.
 *
 * <p>※ 평문이다. 키 파일({@value #FILE_NAME})은 {@code .gitignore} 에 등록돼 커밋되지 않는다. 개인 PC
 * 로컬 토이용이라 허용하지만, 공용 PC 나 저장소 공유 시에는 주의(다 쓰면 파일을 지우면 된다).
 */
public final class Settings {

    /** 작업 디렉터리(보통 게임 실행 폴더)에 두는 키 파일 이름. */
    private static final String FILE_NAME = "gemini-api-key.txt";
    private static final Path KEY_FILE = Path.of(FILE_NAME);

    private Settings() {
    }

    /** 저장된 제미나이 API 키(없거나 읽기 실패면 빈 문자열). */
    public static String geminiApiKey() {
        try {
            if (Files.exists(KEY_FILE)) {
                return Files.readString(KEY_FILE, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException ignored) {
            // 읽기 실패 → 키 없음으로 취급(게임은 계속, LLM 은 규칙봇으로 폴백).
        }
        return "";
    }

    /** 제미나이 API 키 저장(공백이면 파일 삭제). 저장 실패는 조용히 무시한다. */
    public static void setGeminiApiKey(String key) {
        try {
            if (key == null || key.isBlank()) {
                Files.deleteIfExists(KEY_FILE);
            } else {
                Files.writeString(KEY_FILE, key.trim(), StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            // 저장 실패해도 이번 판은 입력값으로 진행되고, 다음 실행 때 다시 입력하면 된다.
        }
    }
}
