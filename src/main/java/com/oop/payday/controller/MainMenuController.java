package com.oop.payday.controller;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.oop.payday.app.GameApp;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.GameServer;
import com.oop.payday.net.NetMessage;
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

    private static String savedNickname = null;

    @FXML private StackPane menuOverlay;
    @FXML private TextField nicknameField;

    private Node cachedScoreTablePanel;
    private Node cachedRulebookPanel;

    @FXML
    private void initialize() {
        String initial = savedNickname != null ? savedNickname : System.getProperty("user.name", "");
        nicknameField.setText(initial);
        nicknameField.textProperty().addListener((obs, old, val) -> savedNickname = val);
        nicknameField.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) Platform.runLater(nicknameField::deselect);
        });
    }

    @FXML
    private void onStartGame() {
        try {
            GameApp.get().showLobby(nickname());
        } catch (IOException ignored) {
        }
    }

    @FXML
    private void onJoinGame() {
        showJoinInput();
    }

    private String nickname() {
        String text = nicknameField.getText().trim();
        return text.isEmpty() ? System.getProperty("user.name", "플레이어") : text;
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

        // 취소 후 뒤늦게 성공한 연결이 화면을 가로채지 않도록 다이얼로그별 취소 표식을 둔다.
        AtomicBoolean cancelled = new AtomicBoolean();
        Button cancelBtn = new Button("취소");
        cancelBtn.getStyleClass().add("menu-button");
        cancelBtn.setOnAction(e -> {
            cancelled.set(true);
            hideOverlay();
        });

        connectBtn.setOnAction(e -> doConnect(
                ipField.getText().trim(), nickname(),
                connectStatus, connectBtn, cancelled));

        HBox btnRow = new HBox(12, cancelBtn, connectBtn);
        btnRow.setAlignment(Pos.CENTER);

        Label title = new Label("네트워크 접속");
        title.getStyleClass().add("lobby-title");
        panel.getChildren().addAll(title, hint, ipField, connectStatus, btnRow);
        showOverlayPanel(panel);
    }

    private void doConnect(String hostInput, String name, Label connectStatus,
            Button connectBtn, AtomicBoolean cancelled) {
        connectStatus.setText("연결 중…");
        connectBtn.setDisable(true);  // 시도 중 중복 접속 방지
        Thread t = new Thread(() -> {
            HostPort target = parseHostPort(hostInput);
            GameClient client = new GameClient();
            try {
                client.connect(target.host(), target.port());
                if (!name.isBlank()) {
                    client.send(new NetMessage.LobbyHello(name));
                }
                Platform.runLater(() -> {
                    if (cancelled.get()) {
                        // 기다리다 취소한 사용자 — 늦게 성공한 연결은 조용히 닫는다.
                        try { client.close(); } catch (IOException ignored) {}
                        return;
                    }
                    try {
                        GameApp.get().showClientLobby(client);
                    } catch (IOException e) {
                        connectStatus.setText("대기실 전환 실패: " + e.getMessage());
                        connectBtn.setDisable(false);
                    }
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    connectStatus.setText("접속 실패: " + e.getMessage());
                    connectBtn.setDisable(false);
                });
            }
        }, "client-connect");
        t.setDaemon(true);
        t.start();
    }

    /** {@code host[:port]} 입력. 포트가 없으면 기본 포트. */
    private record HostPort(String host, int port) {}

    /**
     * 접속 입력을 호스트/포트로 파싱한다. IPv4·호스트명은 {@code 주소:포트},
     * IPv6 리터럴은 {@code [주소]:포트} 또는 주소 단독(콜론 여러 개) 형식을 지원한다.
     */
    private static HostPort parseHostPort(String input) {
        String host = input;
        int port = GameServer.DEFAULT_PORT;
        if (input.startsWith("[")) {
            int end = input.indexOf(']');
            if (end > 0) {
                host = input.substring(1, end);
                if (input.startsWith(":", end + 1)) {
                    port = parsePort(input.substring(end + 2), port);
                }
            }
        } else {
            int idx = input.lastIndexOf(':');
            if (idx > 0 && input.indexOf(':') == idx) {  // 콜론 1개 = 주소:포트, 여러 개 = IPv6 단독
                host = input.substring(0, idx);
                port = parsePort(input.substring(idx + 1), port);
            }
        }
        return new HostPort(host, port);
    }

    private static int parsePort(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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

    private void showOverlayPanel(Node panel) {
        menuOverlay.getStyleClass().setAll("screen-overlay");
        menuOverlay.setMouseTransparent(false);
        menuOverlay.setPickOnBounds(true);
        StackPane.setAlignment(panel, Pos.CENTER);
        menuOverlay.getChildren().setAll(panel);
    }
}
