package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

/**
 * 매 Envelope 에 붙어 전달되는 공개 보드 전체 스냅샷.
 * 클라이언트는 이 값으로 미러 상태를 갱신한 뒤 이벤트를 처리한다.
 * {@code discardPile} 은 환금 컨텍스트·도우미 효과에 쓰인다.
 */
public record PublicBoardState(
        List<TeamStateDto> teams,
        List<CardDto> discardPile) implements Serializable {}
