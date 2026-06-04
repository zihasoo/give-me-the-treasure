package com.oop.payday.net;

import java.io.Serializable;

import com.oop.payday.model.helper.HelperKind;

/**
 * 도우미 카드의 직렬화 가능한 DTO.
 * 상대 플레이어의 미사용 도우미는 {@code kind=null} 로 보내 종류를 숨긴다(뒷면 처리).
 */
public record HelperDto(int id, HelperKind kind, boolean used) implements Serializable {}
