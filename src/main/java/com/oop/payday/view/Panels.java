package com.oop.payday.view;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.WildCard;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 보드 화면의 패널들이 공유하는 작은 무상태 팩토리 모음.
 *
 * <p>{@code GameBoardController} 와 분리된 패널 빌더({@link SplitPanelBuilder},
 * {@link ScorePanels}, {@code CashInPanel})가 함께 쓰던 안내 박스·묶음 박스·경고 다이얼로그를
 * 한곳에 모아 중복을 없앤다. 컨트롤러 상태에 의존하지 않으므로 전부 static 이다.
 */
public final class Panels {

    private Panels() {}

    /** 중앙 패널 공통 루트. 상단에 안내 문구(guide)를 단 세로 박스를 만든다. */
    public static VBox panelRoot(String guide) {
        VBox root = new VBox(18);
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(24));
        root.setMaxWidth(1120);
        root.setMaxHeight(Region.USE_PREF_SIZE);
        root.setMinHeight(Region.USE_PREF_SIZE);
        Label g = new Label(guide);
        g.getStyleClass().add("guide");
        g.setWrapText(true);
        root.getChildren().add(g);
        return root;
    }

    /** 상대를 기다리는 동안 중앙에 띄우는 안내 패널. */
    public static Node waitingPanel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("waiting-label");
        label.setWrapText(true);

        StackPane panel = new StackPane(label);
        panel.getStyleClass().add("waiting-panel");
        panel.setPadding(new Insets(28));
        panel.setMaxWidth(680);
        panel.setMinHeight(180);
        return panel;
    }

    /**
     * 카드 묶음 박스. 공개 카드들을 펼치고, 비공개 카드가 있으면 뒷면 와일드 한 장을 덧붙인다.
     * {@code controls} 로 선택 버튼 등을 박스 하단에 추가할 수 있다.
     */
    public static VBox bundleBox(String title, List<Card> visibleCards, boolean hasFaceDown, Node... controls) {
        FlowPane cards = new FlowPane(8, 8);
        cards.setAlignment(Pos.CENTER);
        cards.setPrefWrapLength(360);
        cards.setMaxWidth(360);
        for (Card c : visibleCards) {
            cards.getChildren().add(new CardView(c, true, true));
        }
        if (hasFaceDown) {
            cards.getChildren().add(new CardView(new WildCard(-1), false, true));
        }

        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("bundle-box");
        box.setPadding(new Insets(12));
        box.setMaxWidth(400);
        box.getChildren().addAll(new Label(title), cards);
        box.getChildren().addAll(controls);
        return box;
    }

    /** 경고용 모달 알림. */
    public static void alert(String message) {
        Alert a = new Alert(AlertType.WARNING, message);
        a.setHeaderText(null);
        a.setTitle("알림");
        a.showAndWait();
    }
}
