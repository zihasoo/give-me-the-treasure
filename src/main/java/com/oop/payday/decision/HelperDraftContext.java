package com.oop.payday.decision;

import com.oop.payday.model.officer.OfficerTile;

/**
 * 봇 전략이 도우미 드래프트(준비 단계)를 결정할 때 보는 읽기 전용 상황값.
 *
 * <p><b>봇 전용 컨텍스트</b>다 — 네트워크로 직렬화되지 않으며(원격 사람의 도우미 선택은
 * {@code onRequestHelpers} 별도 경로를 탄다), 사람/네트워크 플레이어는 이 컨텍스트를 무시한다.
 *
 * <p>같은 도우미 후보라도 인원수·보유 한도·리더 효과·승리 코인에 따라 가치가 달라지므로
 * (예: 1v1 한도 5/6에서 TUSKER/DOUG/VIPER 가치 상승, ALPHA는 숫자1 추적 전략과 결합 시 상승),
 * 정적 랭킹에 상황 보정을 더하게 한다.
 *
 * @param teamSize      이 팀의 인원수(1 = 1인 팀, 2 = 2인 팀)
 * @param holdLimit     이 리더의 보물 보유 한도(FLANKY 보너스 반영)
 * @param officer       이 리더의 간부 타일(없거나 리더 효과 비활성이면 {@code null})
 * @param winningCoins  승리 목표 코인
 */
public record HelperDraftContext(
        int teamSize,
        int holdLimit,
        OfficerTile officer,
        int winningCoins) {
}
