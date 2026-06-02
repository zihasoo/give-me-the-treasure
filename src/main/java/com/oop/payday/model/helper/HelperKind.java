package com.oop.payday.model.helper;

/**
 * 도우미 9종의 식별자와 표시 정보.
 */
public enum HelperKind {
    CUCKOO("음속의 쿠쿠", "같은 색깔 카드 3장 이상 세트 환금 시 3코인"),
    LEO("닌자 레오", "같은 숫자 카드 3장 이상 세트 환금 시 3코인"),
    LUCKY("명견 럭키", "같은 색깔 카드 5장 세트 환금 시 7코인"),
    ALPHA("경찰견 알파", "와일드 없이 숫자 1 보물 4장 세트 환금 시 즉시 승리"),
    DOUG("샛길의 더그", "저주가 아닌 보관 카드를 모두 처분하고 같은 장수만큼 드로우"),
    TUSKER("완력의 투스커", "카드 1장 드로우, 이번 라운드 보유 한도 무시"),
    VIPER("척후 바이퍼", "저주받은 그림을 모두 무료 처분하고 장수만큼 코인"),
    JUNK_DEALER("검은 날개 고물상", "버림 더미의 굉장한 보물을 가져오고 이번 라운드 환금 금지"),
    CROC_BROTHERS("크록 형제", "이미 사용된 도우미 1장의 효과를 복사");

    private final String korean;
    private final String effectText;

    HelperKind(String korean, String effectText) {
        this.korean = korean;
        this.effectText = effectText;
    }

    public String korean() {
        return korean;
    }

    public String effectText() {
        return effectText;
    }
}
