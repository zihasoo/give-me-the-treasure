package com.oop.payday.model.helper;

/**
 * 도우미 카드의 공통 추상 타입. 각 도우미는 환금 단계의 도움 요청으로 한 번만 사용된다.
 */
public abstract class HelperCard {

    private final int id;
    private final HelperKind kind;
    private boolean used;

    protected HelperCard(int id, HelperKind kind) {
        this.id = id;
        this.kind = kind;
    }

    public int id() {
        return id;
    }

    public HelperKind kind() {
        return kind;
    }

    public String displayName() {
        return kind.korean();
    }

    public String effectText() {
        return kind.effectText();
    }

    public boolean isUsed() {
        return used;
    }

    public boolean canUse(HelperUseContext context) {
        return !used;
    }

    public final void use(HelperUseContext context) {
        if (!canUse(context)) {
            throw new IllegalStateException(displayName() + " 효과를 사용할 수 없습니다.");
        }
        apply(context);
        used = true;
    }

    protected abstract void apply(HelperUseContext context);

    @Override
    public String toString() {
        return displayName();
    }
}
