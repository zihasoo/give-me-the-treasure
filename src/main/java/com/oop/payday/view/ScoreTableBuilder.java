package com.oop.payday.view;

import java.util.ArrayList;
import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.set.SetType;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public final class ScoreTableBuilder {

    private ScoreTableBuilder() {}

    public static Node build(Runnable onClose) {
        VBox root = new VBox(18);
        root.getStyleClass().add("score-table-panel");
        root.setAlignment(Pos.TOP_CENTER);
        root.setMaxWidth(1060);
        root.setMaxHeight(Region.USE_PREF_SIZE);

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("조합표");
        title.getStyleClass().add("score-table-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("닫기");
        close.getStyleClass().add("score-table-close");
        close.setOnAction(e -> onClose.run());
        header.getChildren().addAll(title, spacer, close);

        root.getChildren().addAll(header, buildColumns());
        return root;
    }

    /** 환금표 본체(3개 컬럼)를 닫기 헤더 없이 만든다. */
    private static HBox buildColumns() {
        HBox columns = new HBox(18);
        columns.setAlignment(Pos.TOP_CENTER);
        columns.getChildren().addAll(
                column("같은 숫자",
                        List.of(row(SetType.SAME_NUMBER, 2, 2),
                                row(SetType.SAME_NUMBER, 3, 5),
                                row(SetType.SAME_NUMBER, 4, 10))),
                column("연속된 숫자",
                        List.of(row(SetType.RUN, 3, 4),
                                row(SetType.RUN, 4, 6),
                                row(SetType.RUN, 5, 10))),
                column("연속 + 같은 색",
                        List.of(row(SetType.RUN_SAME_COLOR, 3, 6),
                                row(SetType.RUN_SAME_COLOR, 4, 9),
                                row(SetType.RUN_SAME_COLOR, 5, 15))));
        return columns;
    }

    private static VBox column(String title, List<Node> rows) {
        Label heading = new Label(title);
        heading.getStyleClass().add("score-table-heading");
        VBox col = new VBox(10);
        col.getStyleClass().add("score-table-column");
        col.setAlignment(Pos.TOP_CENTER);
        col.getChildren().add(heading);
        col.getChildren().addAll(rows);
        return col;
    }

    private static Node row(SetType type, int size, int score) {
        HBox rowBox = new HBox(10);
        rowBox.getStyleClass().add("score-table-row");
        rowBox.setAlignment(Pos.CENTER_LEFT);

        HBox cards = new HBox(-56);
        cards.setAlignment(Pos.CENTER_LEFT);
        for (Card card : exampleCards(type, size)) {
            cards.getChildren().add(new CardView(card, true, true));
        }

        Label points = new Label(String.valueOf(score));
        points.getStyleClass().add("score-table-points");
        Label unit = new Label("코인");
        unit.getStyleClass().add("score-table-unit");
        rowBox.getChildren().addAll(cards, points, unit);
        return rowBox;
    }

    private static List<Card> exampleCards(SetType type, int size) {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            CardColor color = switch (type) {
                case SAME_NUMBER -> switch (i) {
                    case 0 -> CardColor.YELLOW;
                    case 1 -> CardColor.RED;
                    case 2 -> CardColor.BLUE;
                    default -> CardColor.TEAL;
                };
                case RUN -> switch (i) {
                    case 0 -> CardColor.YELLOW;
                    case 1 -> CardColor.RED;
                    case 2 -> CardColor.BLUE;
                    case 3 -> CardColor.TEAL;
                    default -> CardColor.YELLOW;
                };
                case RUN_SAME_COLOR -> CardColor.RED;
            };
            int number = type == SetType.SAME_NUMBER ? 5 : i + 3;
            cards.add(new TreasureCard(9000 + type.ordinal() * 100 + size * 10 + i, color, number));
        }
        return cards;
    }
}
