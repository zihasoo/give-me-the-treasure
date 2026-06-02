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

    public static final double WIDTH = 78;
    public static final double HEIGHT = 112;

    private final Card card;
    private boolean selected;

    public CardView(Card card, boolean faceUp) {
        this.card = card;
        setPrefSize(WIDTH, HEIGHT);
        setMinSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        Rectangle clip = new Rectangle(WIDTH, HEIGHT);
        clip.setArcWidth(14);
        clip.setArcHeight(14);
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
            subText = "저주받은 그림";
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
        Label top = cornerLabel(cornerText, textColor);
        StackPane.setAlignment(top, Pos.TOP_LEFT);
        StackPane.setMargin(top, new Insets(8, 0, 0, 8));

        Label bottom = cornerLabel(cornerText, textColor);
        bottom.setRotate(180);
        StackPane.setAlignment(bottom, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(bottom, new Insets(0, 8, 8, 0));

        Label icon = new Label(iconText);
        icon.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: " + textColor
                + "; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 2, 0, 1, 1);");
        Label sub = new Label(subText);
        sub.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: " + textColor + ";");
        sub.setWrapText(true);
        sub.setTextAlignment(TextAlignment.CENTER);

        StackPane face = new StackPane();
        face.setPrefSize(WIDTH, HEIGHT);
        face.setMinSize(WIDTH, HEIGHT);
        face.setMaxSize(WIDTH, HEIGHT);
        face.setStyle("-fx-background-color: #11100e, " + bg + ";"
                + " -fx-background-insets: 0, 3;"
                + " -fx-background-radius: 7, 4;"
                + " -fx-border-color: #11100e; -fx-border-width: 0; -fx-border-radius: 7;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 4, 0, 1, 2);");

        VBox box = new VBox(4, icon, sub);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(WIDTH - 16);
        face.getChildren().setAll(box, top, bottom);
        getChildren().setAll(face);
    }

    private void renderBack() {
        Label lock = new Label("▣");
        lock.setStyle("-fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: #f1c15d;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 2, 0, 1, 1);");
        Label plank = new Label("━\n━\n━");
        plank.setStyle("-fx-font-size: 20px; -fx-text-fill: rgba(35,18,10,0.55); -fx-line-spacing: 7;");
        plank.setTextAlignment(TextAlignment.CENTER);
        StackPane back = new StackPane();
        back.setPrefSize(WIDTH, HEIGHT);
        back.setMinSize(WIDTH, HEIGHT);
        back.setMaxSize(WIDTH, HEIGHT);
        back.setStyle("-fx-background-color: #11100e, linear-gradient(to bottom, #8a5529, #5b331b 45%, #7a4521), #d7a84e;"
                + " -fx-background-insets: 0, 3, 8;"
                + " -fx-background-radius: 7, 4, 3;"
                + " -fx-border-color: transparent; -fx-border-width: 0;"
                + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 4, 0, 1, 2);");
        back.getChildren().setAll(plank, lock);
        back.setAlignment(Pos.CENTER);
        getChildren().setAll(back);
    }

    private Label cornerLabel(String text, String textColor) {
        Label label = new Label(text);
        label.setPadding(Insets.EMPTY);
        label.setMinSize(18, 22);
        label.setAlignment(Pos.CENTER);
        label.setStyle("-fx-font-family: 'Arial Black', 'Malgun Gothic';"
                + " -fx-font-size: 17px; -fx-font-weight: 900; -fx-text-fill: " + textColor
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
