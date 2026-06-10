package com.oop.payday.controller;

import java.io.IOException;

import com.oop.payday.app.GameApp;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.GameServer;
import com.oop.payday.view.RulebookBuilder;
import com.oop.payday.view.ScoreTableBuilder;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 메인 메뉴 컨트롤러. {@code 게임 시작}(호스트 대기실)·{@code 접속하기}(원격 대기실 입장)를 처리한다.
 */
public class MainMenuController {

    @FXML private StackPane menuOverlay;

    private Node cachedScoreTablePanel;
    private Node cachedRulebookPanel;

    @FXML
    private void onStartGame() {
        try {
            GameApp.get().showLobby();
        } catch (IOException ignored) {
        }
    }

    @FXML
    private void onJoinGame() {
        showJoinInput();
    }

    @FXML
    private void onScoreTable() {
        if (cachedScoreTablePanel == null) {
            cachedScoreTablePanel = ScoreTableBuilder.build(this::hideOverlay);
        }
        showOverlayPanel(cachedScoreTablePanel);
    }

    @FXML
    private void onRulebook() {
        if (cachedRulebookPanel == null) {
            cachedRulebookPanel = RulebookBuilder.build(this::hideOverlay);
        }
        showOverlayPanel(cachedRulebookPanel);
    }

    private void hideOverlay() {
        menuOverlay.getChildren().clear();
        menuOverlay.getStyleClass().clear();
        menuOverlay.setMouseTransparent(true);
        menuOverlay.setPickOnBounds(false);
    }

    // ── 접속하기 (원격 대기실 입장) ──────────────────────────────────

    private void showJoinInput() {
        VBox panel = lobbyPanel();

        Label hint = new Label("호스트의 IP 주소를 입력하세요:");
        hint.getStyleClass().add("preview");
        TextField ipField = new TextField("127.0.0.1");
        ipField.setMaxWidth(300);

        Label connectStatus = new Label();
        connectStatus.getStyleClass().add("status");

        Button connectBtn = new Button("접속");
        connectBtn.getStyleClass().add("menu-button");
        connectBtn.setOnAction(e -> doConnect(ipField.getText().trim(), connectStatus));

        HBox btnRow = new HBox(12, cancelButton(), connectBtn);
        btnRow.setAlignment(Pos.CENTER);

        Label title = new Label("네트워크 접속");
        title.getStyleClass().add("lobby-title");
        panel.getChildren().addAll(title, hint, ipField, connectStatus, btnRow);
        showOverlayPanel(panel);
    }

    private void doConnect(String hostInput, Label connectStatus) {
        connectStatus.setText("연결 중…");
        Thread t = new Thread(() -> {
            String host = hostInput;
            int port = GameServer.DEFAULT_PORT;
            if (hostInput.contains(":")) {
                String[] parts = hostInput.split(":");
                host = parts[0];
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                }
            }

            GameClient client = new GameClient();
            try {
                client.connect(host, port);
                Platform.runLater(() -> {
                    try {
                        GameApp.get().showClientLobby(client);
                    } catch (IOException e) {
                        connectStatus.setText("대기실 전환 실패: " + e.getMessage());
                    }
                });
            } catch (IOException e) {
                Platform.runLater(() -> connectStatus.setText("접속 실패: " + e.getMessage()));
            }
        }, "client-connect");
        t.setDaemon(true);
        t.start();
    }

    // ── 공통 UI 헬퍼 ────────────────────────────────────────────────

    private VBox lobbyPanel() {
        VBox panel = new VBox(12);
        panel.setAlignment(Pos.CENTER);
        panel.setMaxWidth(400);
        panel.setMaxHeight(VBox.USE_PREF_SIZE);
        panel.getStyleClass().addAll("menu-card", "network-lobby-card");
        return panel;
    }

    private Button cancelButton() {
        Button btn = new Button("취소");
        btn.getStyleClass().add("menu-button");
        btn.setOnAction(e -> hideOverlay());
        return btn;
    }

    private void showOverlayPanel(Node panel) {
        menuOverlay.getStyleClass().setAll("screen-overlay");
        menuOverlay.setMouseTransparent(false);
        menuOverlay.setPickOnBounds(true);
        StackPane.setAlignment(panel, Pos.CENTER);
        menuOverlay.getChildren().setAll(panel);
    }
}
