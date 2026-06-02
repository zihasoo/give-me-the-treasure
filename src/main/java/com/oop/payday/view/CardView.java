package com.oop.payday.view;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.StealCard;
import com.oop.payday.model.card.TreasureCard;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * 카드 한 장의 시각 표현(재사용 컴포넌트). 색·숫자·특수 종류에 따라 다르게 그리며,
 * 앞면/뒷면과 선택 상태를 표시한다. 클릭 동작은 외부(컨트롤러)에서 부여한다.
 */
public final class CardView extends StackPane {

    /** 카드 필드(각자 필드 영역)에 사용하는 크기. */
    public static final double WIDTH = 90;
    public static final double HEIGHT = 128;

    /** 패널(분할·선택·환금 UI)에 사용하는 크기 — 필드 크기의 약 87%. */
    public static final double PANEL_WIDTH = 78;
    public static final double PANEL_HEIGHT = 111;

    private final Card card;
    private boolean selected;
    private final double w;
    private final double h;

    /** 카드 필드용 — 큰 크기. */
    public CardView(Card card, boolean faceUp) {
        this(card, faceUp, WIDTH, HEIGHT);
    }

    /** 패널용 — {@code panelSize=true} 이면 작은 크기 사용. */
    public CardView(Card card, boolean faceUp, boolean panelSize) {
        this(card, faceUp, panelSize ? PANEL_WIDTH : WIDTH, panelSize ? PANEL_HEIGHT : HEIGHT);
    }

    private CardView(Card card, boolean faceUp, double w, double h) {
        this.card = card;
        this.w = w;
        this.h = h;
        setPrefSize(w, h);
        setMinSize(w, h);
        setMaxSize(w, h);
        double arc = 14 * w / WIDTH;
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        setClip(clip);
        getStyleClass().add("card");
        if (faceUp) {
            renderFront();
        } else {
            renderBack();
        }
    }

    public Card card() {
        return card;
    }

    public boolean isSelected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
        if (selected) {
            if (!getStyleClass().contains("card-selected")) {
                getStyleClass().add("card-selected");
            }
        } else {
            getStyleClass().remove("card-selected");
        }
    }

    public void toggleSelected() {
        setSelected(!selected);
    }

    private void renderFront() {
        double scale = w / WIDTH;
        int iconSize   = (int) Math.round(38 * scale);
        int subSize    = (int) Math.round(11 * scale);
        int cornerSize = (int) Math.round(19 * scale);
        int cornerMinW = (int) Math.round(18 * scale);
        int cornerMinH = (int) Math.round(22 * scale);
        double cornerMarginX = 8 * scale;
        double cornerMarginY = 8 * scale;

        String bg;
        String cornerText;
        String iconText;
        String subText;
        boolean darkText = false;

        if (card instanceof TreasureCard t) {
            bg = colorHex(t.color());
            cornerText = String.valueOf(t.number());
            iconText = iconFor(t.color());
            subText = t.color().korean();
            darkText = t.color() == CardColor.YELLOW;
        } else if (card.isWild()) {
            bg = "linear-gradient(to bottom right, #f26aa8, #9a4cc2 55%, #2a2238)";
            cornerText = "★";
            iconText = "✦";
            subText = "굉장한 보물";
        } else if (card instanceof CursedCard cursed) {
            bg = "linear-gradient(to bottom right, #8060a9, #3b294d)";
            cornerText = String.valueOf(cursed.number());
            iconText = "▧";
            subText = "저주 그림";
        } else if (card instanceof StealCard) {
            bg = "linear-gradient(to bottom right, #6a4526, #2c1d15)";
            cornerText = "↪";
            iconText = "♣";
            subText = "슬쩍하기";
        } else {
            bg = "#7f8c8d";
            cornerText = "!";
            iconText = "?";
            subText = card.displayName();
        }

        String textColor = darkText ? "#211914" : "#fff7de";
        Label top = cornerLabel(cornerText, textColor, cornerSize, cornerMinW, cornerMinH);
        StackPane.setAlignment(top, Pos.TOP_LEFT);
        StackPane.setMargin(top, new Insets(cornerMarginY, 0, 0, cornerMarginX));

        Label bottom = cornerLabel(cornerText, textColor, cornerSize, cornerMinW, cornerMinH);
        bottom.setRotate(180);
        StackPane.setAlignment(bottom, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(bottom, new Insets(0, cornerMarginX, cornerMarginY, 0));

        Label icon = new Label(iconText);
        icon.setStyle("-fx-font-size: " + iconSize + "px; -fx-font-weight: bold; -fx-text-fill: " + textColor
                + "; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 2, 0, 1, 1);");
        Label sub = new Label(subText);
        sub.setStyle("-fx-font-size: " + subSize + "px; -fx-font-weight: bold; -fx-text-fill: " + textColor + ";");
        sub.setWrapText(true);
        sub.setTextAlignment(TextAlignment.CENTER);

        StackPane face = new StackPane();
        face.setPrefSize(w, h);
        face.setMinSize(w, h);
        face.setMaxSize(w, h);
        face.setStyle("-fx-background-color: #11100e, " + bg + ";"
                + " -fx-background-insets: 0, 3;"
                + " -fx-background-radius: 7, 4;"
                + " -fx-border-color: #11100e; -fx-border-width: 0; -fx-border-radius: 7;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 4, 0, 1, 2);");

        VBox box = new VBox(4 * scale, icon, sub);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(w - 16 * scale);
        face.getChildren().setAll(box, top, bottom);
        getChildren().setAll(face);
    }

    private void renderBack() {
        double scale = w / WIDTH;
        int lockSize  = (int) Math.round(38 * scale);
        int plankSize = (int) Math.round(22 * scale);
        double plankSpacing = 7 * scale;

        Label lock = new Label("▣");
        lock.setStyle("-fx-font-size: " + lockSize + "px; -fx-font-weight: bold; -fx-text-fill: #f1c15d;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 2, 0, 1, 1);");
        Label plank = new Label("━\n━\n━");
        plank.setStyle("-fx-font-size: " + plankSize + "px; -fx-text-fill: rgba(35,18,10,0.55); -fx-line-spacing: " + plankSpacing + ";");
        plank.setTextAlignment(TextAlignment.CENTER);
        StackPane back = new StackPane();
        back.setPrefSize(w, h);
        back.setMinSize(w, h);
        back.setMaxSize(w, h);
        back.setStyle("-fx-background-color: #11100e, linear-gradient(to bottom, #8a5529, #5b331b 45%, #7a4521), #d7a84e;"
                + " -fx-background-insets: 0, 3, 8;"
                + " -fx-background-radius: 7, 4, 3;"
                + " -fx-border-color: transparent; -fx-border-width: 0;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 4, 0, 1, 2);");
        back.getChildren().setAll(plank, lock);
        back.setAlignment(Pos.CENTER);
        getChildren().setAll(back);
    }

    private Label cornerLabel(String text, String textColor, int fontSize, int minW, int minH) {
        Label label = new Label(text);
        label.setPadding(Insets.EMPTY);
        label.setMinSize(minW, minH);
        label.setAlignment(Pos.CENTER);
        label.setStyle("-fx-font-family: 'Arial Black', 'Malgun Gothic';"
                + " -fx-font-size: " + fontSize + "px; -fx-font-weight: 900; -fx-text-fill: " + textColor
                + "; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.45), 1, 0, 0.8, 0.8);");
        return label;
    }

    private static String colorHex(CardColor color) {
        return switch (color) {
            case YELLOW -> "#efe17a";
            case RED -> "#d94b3f";
            case TEAL -> "#81cdbb";
            case BLUE -> "#3d99d4";
        };
    }

    private static String iconFor(CardColor color) {
        return switch (color) {
            case YELLOW -> "◉";
            case RED -> "†";
            case TEAL -> "♧";
            case BLUE -> "▣";
        };
    }
}
