package com.oop.payday.net;

import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperUseContext;

/**
 * 클라이언트 측 도우미 카드 미러. 렌더링 전용이라 {@code apply} 는 지원하지 않는다.
 *
 * <p>{@code mirrorKind == null} 이면 상대 플레이어의 미사용 도우미(종류 비공개 = 뒷면).
 * 서버가 공개하면 {@link #revealKind} 로 갱신한다. kind/used 모두 가변이라 재생성 없이 인플레이스 갱신.
 */
public final class MirrorHelperCard extends HelperCard {

    private HelperKind mirrorKind;
    private boolean mirrorUsed;

    public MirrorHelperCard(int id, HelperKind kind) {
        // 부모의 private kind 필드는 더미로 채우고, 실제 표시는 override 메서드로 제공
        super(id, kind != null ? kind : HelperKind.CUCKOO);
        this.mirrorKind = kind;
    }

    @Override
    public HelperKind kind() {
        return mirrorKind;
    }

    @Override
    public String displayName() {
        return mirrorKind != null ? mirrorKind.korean() : "???";
    }

    @Override
    public String effectText() {
        return mirrorKind != null ? mirrorKind.effectText() : "";
    }

    @Override
    public boolean isUsed() {
        return mirrorUsed;
    }

    public void revealKind(HelperKind kind) {
        this.mirrorKind = kind;
    }

    public void markUsed() {
        mirrorUsed = true;
    }

    @Override
    protected void apply(HelperUseContext context) {
        throw new UnsupportedOperationException("클라이언트 측에서 호출 불가");
    }
}
