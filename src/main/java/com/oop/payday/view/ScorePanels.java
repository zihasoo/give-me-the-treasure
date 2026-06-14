package com.oop.payday.view;

import com.oop.payday.game.Team;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * 게임 종료 화면 패널(양 팀 점수 카드 + 다시하기/나가기 버튼).
 *
 * <p>{@code GameBoardController} 에서 분리한 무상태 뷰 빌더. 보드 레이아웃대로 상대 팀(teamB)을
 * 위, 우리 팀(teamA)을 아래에 둔다. 다시하기 노출 여부와 동작은 {@code showRestart}/{@code onRestart}
 * 로 받는다(네트워크 클라이언트는 호스트의 재시작을 기다리므로 다시하기 대신 안내만 표시).
 */
public final class ScorePanels {

    private ScorePanels() {}

    public static Node gameOver(Team teamA, Team teamB, Team winner, boolean showRestart,
            Runnable onRestart, Runnable onExit) {
        VBox root = Panels.panelRoot("게임 종료");
        root.setSpacing(16);

        // 보드 레이아웃 반영: teamB(상대) = 위, teamA(나) = 아래
        VBox topCard    = buildScoreCard(teamB, teamB == winner, false);
        VBox bottomCard = buildScoreCard(teamA, teamA == winner, true);

        VBox scoreColumn = new VBox(12, topCard, bottomCard);
        scoreColumn.setAlignment(Pos.CENTER);
        scoreColumn.setMaxWidth(440);

        Button exit = new Button("나가기");
        exit.getStyleClass().add("menu-button");
        exit.setOnAction(e -> onExit.run());

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER);
        // 다시하기: 오프라인·호스트만. 네트워크 클라이언트는 호스트의 재시작을 기다린다.
        if (showRestart) {
            Button restart = new Button("다시하기");
            restart.getStyleClass().add("menu-button");
            restart.setOnAction(e -> onRestart.run());
            actions.getChildren().add(restart);
        }
        actions.getChildren().add(exit);

        root.getChildren().addAll(scoreColumn, actions);
        if (!showRestart) {
            Label note = new Label("호스트가 다시 시작할 수 있습니다.");
            note.getStyleClass().add("guide");
            root.getChildren().add(note);
        }
        return root;
    }

    private static VBox buildScoreCard(Team team, boolean win, boolean isBottom) {
        Label badge = new Label(win ? "승리" : "패배");
        badge.getStyleClass().add(win ? "score-win-badge" : "score-lose-badge");

        Label coins = new Label(String.valueOf(team.coins()));
        coins.getStyleClass().add(win ? "score-coins-win" : "score-coins-lose");

        Label name = new Label(team.name());
        name.getStyleClass().add("score-name");

        VBox card;
        if (isBottom) {
            card = new VBox(8, coins, badge, name);
        } else {
            card = new VBox(8, name, badge, coins);
        }
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().addAll("score-card", win ? "score-card-win" : "score-card-lose");
        card.setPadding(new Insets(18, 36, 18, 36));
        return card;
    }
}
