package com.oop.payday.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/**
 * 리더가 가져온 손패를 두 묶음으로 나누는 분할 패널(규칙 §5). 카드를 드래그해 묶음을 옮기고,
 * 우클릭으로 비공개(face-down) 카드를 지정한다.
 *
 * <p>{@code GameBoardController} 에서 분리한 무상태 뷰 빌더. 드래그/드롭·비공개 선택 상태는
 * 전부 {@link #build} 안의 지역 변수로 닫혀 있어 컨트롤러 상태에 의존하지 않는다. 완성된
 * {@link SplitDecision} 은 {@code onSubmit} 콜백으로 돌려준다.
 */
public final class SplitPanelBuilder {

    private SplitPanelBuilder() {}

    /**
     * 분할 패널을 만든다. 검증을 통과한 결정만 {@code onSubmit} 으로 전달한다
     * (호출자는 보통 중앙 패널을 지우고 게임 스레드에 결정을 넘긴다).
     */
    public static Node build(List<Card> hand, Consumer<SplitDecision> onSubmit) {
        VBox root = Panels.panelRoot("카드를 드래그해 두 묶음으로 나누세요. 우클릭하면 비공개 카드가 됩니다.");

        Map<Card, Integer> bundleOf = new HashMap<>();
        Map<Card, Node> nodeOf = new HashMap<>();
        Card[] faceDown = new Card[1];

        FlowPane bundleA = splitDropZone("묶음 ①");
        FlowPane bundleB = splitDropZone("묶음 ②");

        Runnable refresh = () -> {
            bundleA.getChildren().clear();
            bundleB.getChildren().clear();
            for (Card card : hand) {
                Node node = nodeOf.computeIfAbsent(card, c -> buildDraggableSplitCard(c, bundleOf, faceDown, bundleA, bundleB));
                if (bundleOf.getOrDefault(card, 0) == 0) {
                    bundleA.getChildren().add(node);
                } else {
                    bundleB.getChildren().add(node);
                }
                markSplitCard(node, card == faceDown[0]);
            }
        };

        for (int i = 0; i < hand.size(); i++) {
            bundleOf.put(hand.get(i), i < 2 ? 0 : 1);
        }
        faceDown[0] = hand.get(0);
        installDropTarget(bundleA, 0, bundleOf, refresh);
        installDropTarget(bundleB, 1, bundleOf, refresh);
        refresh.run();

        HBox bundlesRow = new HBox(18,
                splitBundleBox("묶음 ①", bundleA),
                splitBundleBox("묶음 ②", bundleB));
        bundlesRow.setAlignment(Pos.CENTER);

        Button done = new Button("분할 완료");
        done.getStyleClass().add("menu-button");
        done.setOnAction(e -> {
            List<Card> cardsA = new ArrayList<>();
            List<Card> cardsB = new ArrayList<>();
            for (Card card : hand) {
                if (bundleOf.getOrDefault(card, 0) == 0) {
                    cardsA.add(card);
                } else {
                    cardsB.add(card);
                }
            }
            if (faceDown[0] == null) {
                Panels.alert("우클릭으로 비공개 카드 1장을 선택하세요.");
                return;
            }
            SplitDecision decision = new SplitDecision(cardsA, cardsB, faceDown[0]);
            if (!decision.isValid()) {
                Panels.alert("분할이 올바르지 않습니다. 다시 확인하세요.");
                return;
            }
            onSubmit.accept(decision);
        });

        root.getChildren().addAll(bundlesRow, done);
        return root;
    }

    private static FlowPane splitDropZone(String label) {
        FlowPane pane = new FlowPane(10, 10);
        pane.setAlignment(Pos.CENTER);
        pane.setPrefWrapLength(360);
        pane.setMaxWidth(380);
        pane.setMinHeight(135);
        pane.getStyleClass().add("split-drop-zone");
        pane.setUserData(label);
        return pane;
    }

    private static VBox splitBundleBox(String title, FlowPane cards) {
        Label label = new Label(title);
        label.getStyleClass().add("split-title");
        VBox box = new VBox(10, label, cards);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("split-bundle-box");
        box.setPadding(new Insets(12));
        box.setMaxWidth(400);
        return box;
    }

    private static Node buildDraggableSplitCard(Card card, Map<Card, Integer> bundleOf, Card[] faceDown,
            FlowPane bundleA, FlowPane bundleB) {
        StackPane wrapper = new StackPane(new CardView(card, true, true));
        wrapper.getStyleClass().add("split-card");
        wrapper.setUserData(card);

        Label hidden = new Label("비공개");
        hidden.getStyleClass().add("face-down-ribbon");
        StackPane.setAlignment(hidden, Pos.BOTTOM_CENTER);
        wrapper.getChildren().add(hidden);

        wrapper.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                faceDown[0] = card;
                refreshSplitFaceDown(bundleA, bundleB, faceDown[0]);
            }
        });
        wrapper.setOnDragDetected(e -> {
            Dragboard dragboard = wrapper.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(card.id()));
            dragboard.setContent(content);
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage dragImage = wrapper.snapshot(params, null);
            dragboard.setDragView(dragImage, dragImage.getWidth() / 2, dragImage.getHeight() / 2);
            e.consume();
        });
        return wrapper;
    }

    private static void installDropTarget(FlowPane target, int bundleIndex, Map<Card, Integer> bundleOf, Runnable refresh) {
        target.setOnDragOver(e -> {
            if (e.getGestureSource() instanceof Node node
                    && node.getUserData() instanceof Card card
                    && e.getDragboard().hasString()
                    && canMoveSplitCard(card, bundleIndex, bundleOf)) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
        target.setOnDragDropped(e -> {
            Object source = e.getGestureSource();
            if (source instanceof Node node
                    && node.getUserData() instanceof Card card
                    && canMoveSplitCard(card, bundleIndex, bundleOf)) {
                bundleOf.put(card, bundleIndex);
                refresh.run();
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    private static boolean canMoveSplitCard(Card card, int targetBundle, Map<Card, Integer> bundleOf) {
        int currentBundle = bundleOf.getOrDefault(card, 0);
        if (currentBundle == targetBundle) {
            return true;
        }
        long sourceCount = bundleOf.values().stream().filter(index -> index == currentBundle).count();
        long targetCount = bundleOf.values().stream().filter(index -> index == targetBundle).count();
        return sourceCount > 1 && targetCount < 4;
    }

    private static void refreshSplitFaceDown(FlowPane bundleA, FlowPane bundleB, Card faceDown) {
        for (Node node : concatNodes(bundleA.getChildren(), bundleB.getChildren())) {
            markSplitCard(node, node.getUserData() == faceDown);
        }
    }

    private static void markSplitCard(Node node, boolean selected) {
        node.getStyleClass().remove("split-face-down");
        if (selected) {
            if (!node.getStyleClass().contains("split-face-down")) {
                node.getStyleClass().add("split-face-down");
            }
        }
        if (node instanceof StackPane stack) {
            for (Node child : stack.getChildren()) {
                if (child.getStyleClass().contains("face-down-ribbon")) {
                    child.setVisible(selected);
                }
            }
        }
    }

    private static List<Node> concatNodes(List<Node> first, List<Node> second) {
        List<Node> nodes = new ArrayList<>(first.size() + second.size());
        nodes.addAll(first);
        nodes.addAll(second);
        return nodes;
    }
}
