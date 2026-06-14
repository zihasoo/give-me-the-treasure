package com.oop.payday.app;

import java.io.IOException;
import java.net.URL;
import java.util.Objects;

import com.oop.payday.controller.GameBoardController;
import com.oop.payday.controller.LobbyController;
import com.oop.payday.game.MatchSetup;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.GameServer;
import com.oop.payday.net.NetMessage;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

/**
 * JavaFX 애플리케이션 본체. 화면(Scene) 전환을 담당한다.
 *
 * <p>현재는 메인 메뉴만 로드한다. 이후 마일스톤에서 게임 보드 화면 전환을 추가한다.
 */
public class GameApp extends Application {

    /** 리소스(FXML/CSS) 기준 경로. */
    private static final String FXML_BASE = "/com/oop/payday/fxml/";
    private static final String CSS_PATH = "/com/oop/payday/css/style.css";

    private static final String TITLE = "도적단의 월급날";
    private static final double WIDTH = 1440;
    private static final double HEIGHT = 810;
    private static final double MIN_WIDTH = 1440;
    private static final double MIN_HEIGHT = 810;

    private static GameApp instance;

    private Stage primaryStage;

    /** 컨트롤러가 화면 전환을 요청할 수 있도록 현재 앱 인스턴스를 제공한다. */
    public static GameApp get() {
        return instance;
    }

    @Override
    public void start(Stage stage) throws IOException {
        instance = this;
        Font.loadFont(getClass().getResourceAsStream("/com/oop/payday/fonts/BlackHanSans-Regular.ttf"), 10);
        this.primaryStage = stage;
        stage.setTitle(TITLE);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        showScene("main_menu.fxml");
        stage.show();
    }

    /**
     * 지정한 FXML 파일을 로드해 현재 Stage 에 표시한다.
     *
     * @param fxmlFile {@link #FXML_BASE} 기준 파일명 (예: {@code "main_menu.fxml"})
     */
    public void showScene(String fxmlFile) throws IOException {
        applyScene(load(fxmlFile).getRoot());
    }

    /** 호스트 대기실 화면으로 전환한다(서버를 열고 접속을 받는다). */
    public void showLobby(String hostName) throws IOException {
        FXMLLoader loader = load("lobby.fxml");
        applyScene(loader.getRoot());
        LobbyController controller = loader.getController();
        controller.initHost(hostName);
    }

    /** 클라이언트 대기실 화면으로 전환한다(호스트 대기실 상태를 받아 표시). */
    public void showClientLobby(GameClient client) throws IOException {
        FXMLLoader loader = load("lobby.fxml");
        applyScene(loader.getRoot());
        LobbyController controller = loader.getController();
        controller.initClient(client);
    }

    /** 게임 보드 화면으로 전환하고 대기실 구성대로 게임을 시작한다 (오프라인). */
    public void showGameBoard(MatchSetup setup) throws IOException {
        FXMLLoader loader = load("game_board.fxml");
        applyScene(loader.getRoot());
        GameBoardController controller = loader.getController();
        controller.startGame(setup);
    }

    /** 게임 보드 화면으로 전환하고 호스트 모드 게임을 시작한다(대기실 구성·원격 포함). */
    public void showHostGame(MatchSetup setup, GameServer server) throws IOException {
        FXMLLoader loader = load("game_board.fxml");
        applyScene(loader.getRoot());
        GameBoardController controller = loader.getController();
        controller.startHostGame(setup, server);
    }

    /** 게임 보드 화면으로 전환하고 클라이언트 모드 게임을 시작한다(핸드셰이크 수신 시). */
    public void showClientGame(GameClient client, NetMessage.Handshake handshake) throws IOException {
        FXMLLoader loader = load("game_board.fxml");
        applyScene(loader.getRoot());
        GameBoardController controller = loader.getController();
        controller.startClientGame(client, handshake);
    }

    private FXMLLoader load(String fxmlFile) throws IOException {
        URL fxmlUrl = Objects.requireNonNull(
                getClass().getResource(FXML_BASE + fxmlFile),
                "FXML 리소스를 찾을 수 없습니다: " + FXML_BASE + fxmlFile);
        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        loader.load();
        return loader;
    }

    private void applyScene(Parent root) {
        Scene scene = new Scene(root, WIDTH, HEIGHT);
        URL cssUrl = getClass().getResource(CSS_PATH);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        primaryStage.setScene(scene);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
