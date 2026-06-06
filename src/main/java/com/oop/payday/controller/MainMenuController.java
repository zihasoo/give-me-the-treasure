package com.oop.payday.controller;

import java.io.IOException;
import java.net.InetAddress;

import com.oop.payday.app.GameApp;
import com.oop.payday.game.GameConfig;
import com.oop.payday.net.ClientMirror;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.GameServer;
import com.oop.payday.net.NetMessage;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.view.ScoreTableBuilder;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 메인 메뉴 컨트롤러. 봇/네트워크 대전 선택을 처리한다.
 */
public class MainMenuController {

    @FXML private StackPane menuOverlay;

    private Node cachedScoreTablePanel;

    @FXML
    private void onPlayerVsPlayBot() {
        startOfflineGame(false);
    }

    @FXML
    private void onPlayerVsTestBot() {
        startOfflineGame(true);
    }

    @FXML
    private void onNetworkBattle() {
        showNetworkLobby();
    }

    @FXML
    private void onScoreTable() {
        if (cachedScoreTablePanel == null) {
            cachedScoreTablePanel = ScoreTableBuilder.build(this::hideOverlay);
        }
        menuOverlay.getStyleClass().setAll("screen-overlay");
        menuOverlay.setMouseTransparent(false);
        menuOverlay.setPickOnBounds(true);
        StackPane.setAlignment(cachedScoreTablePanel, Pos.CENTER);
        cachedScoreTablePanel.setOpacity(1);
        menuOverlay.getChildren().setAll(cachedScoreTablePanel);
    }

    private void hideOverlay() {
        menuOverlay.getChildren().clear();
        menuOverlay.getStyleClass().clear();
        menuOverlay.setMouseTransparent(true);
        menuOverlay.setPickOnBounds(false);
    }

    private void startOfflineGame(boolean testBot) {
        GameConfig config = testBot ? GameConfig.practice(true) : GameConfig.standard(true);
        try {
            GameApp.get().showGameBoard(config, testBot);
        } catch (IOException ignored) {
        }
    }

    // ── 네트워크 로비 ────────────────────────────────────────────────

    private void showNetworkLobby() {
        VBox panel = lobbyPanel();
        Button hostBtn = new Button("호스트하기");
        hostBtn.getStyleClass().add("menu-mode-button");
        hostBtn.setMaxWidth(Double.MAX_VALUE);
        hostBtn.setOnAction(e -> showHostWaiting());

        Button joinBtn = new Button("접속하기");
        joinBtn.getStyleClass().add("menu-mode-button");
        joinBtn.setMaxWidth(Double.MAX_VALUE);
        joinBtn.setOnAction(e -> showJoinInput());

        Label title1 = new Label("네트워크 대전");
        title1.getStyleClass().add("lobby-title");
        panel.getChildren().addAll(title1, hostBtn, joinBtn, cancelButton());
        showOverlayPanel(panel);
    }

    private void showHostWaiting() {
        GameServer server;
        try {
            server = new GameServer();
        } catch (IOException e) {
            hideOverlay();
            return;
        }

        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception ex) {
            ip = "127.0.0.1";
        }

        Label info = new Label("상대방이 아래 주소로 접속하면 게임이 시작됩니다.\n"
                + ip + ":" + server.port());
        info.setWrapText(true);
        info.getStyleClass().add("preview");
        Label waiting = new Label("연결 대기 중…");
        waiting.getStyleClass().add("status");

        Button cancelBtn = new Button("취소");
        cancelBtn.getStyleClass().add("menu-button");
        cancelBtn.setOnAction(e -> {
            try { server.close(); } catch (IOException ignored) {}
            hideOverlay();
        });

        VBox panel = lobbyPanel();
        Label title2 = new Label("호스트 대기");
        title2.getStyleClass().add("lobby-title");
        panel.getChildren().addAll(title2, info, waiting, cancelBtn);
        showOverlayPanel(panel);

        GameConfig config = GameConfig.standard(false);

        Thread acceptThread = new Thread(() -> {
            try {
                server.acceptClient();
            } catch (IOException e) {
                Platform.runLater(this::hideOverlay);
                return;
            }
            NetworkPlayer networkPlayer = new NetworkPlayer("플레이어 2");
            Platform.runLater(() -> {
                try {
                    GameApp.get().showHostGame(config, server, networkPlayer);
                } catch (IOException ignored) {
                }
            });
        }, "host-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

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

        Label title3 = new Label("네트워크 접속");
        title3.getStyleClass().add("lobby-title");
        panel.getChildren().addAll(title3, hint, ipField, connectStatus, btnRow);
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
                } catch (NumberFormatException ignored) {}
            }
            
            GameClient client = new GameClient();
            try {
                NetMessage.Handshake hs = client.connect(host, port);
                ClientMirror mirror = new ClientMirror();
                mirror.init(hs.clientTeamId(), hs.initialState());
                Platform.runLater(() -> {
                    try {
                        GameApp.get().showClientGame(mirror, client);
                    } catch (IOException e) {
                        connectStatus.setText("게임 화면 전환 실패: " + e.getMessage());
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
