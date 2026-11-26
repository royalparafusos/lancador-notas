package br.com.lancadornotas;

import br.com.lancadornotas.ded.Ded;
import br.com.lancadornotas.ded.DedClient;
import br.com.lancadornotas.ded.DedExtensao;
import br.com.lancadornotas.ui.LoginView;
import br.com.lancadornotas.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    private Ded ded;
    private Stage stage;
    private MainView main;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        String versao = Atualizador.versaoAtual();
        stage.setTitle("Lançador de Notas — DED+ MG" + (versao != null ? "   (versão " + versao + ")" : ""));
        var icone = getClass().getResourceAsStream("/icone.png");
        if (icone != null) stage.getIcons().add(new javafx.scene.image.Image(icone));

        java.util.function.Consumer<String> log = msg -> Platform.runLater(() -> {
            if (main != null) main.log(msg); else System.out.println(msg);
        });
        try {
            if ("navegador".equals(Config.modoDed())) {
                ded = new DedClient(log);
            } else {
                try {
                    ded = new DedExtensao(log);
                } catch (LinkageError falta) {
                    // segurança: se faltar algum componente do Java para o conector, usa o modo antigo
                    // (o programa continua abrindo e recebendo atualizações)
                    System.err.println("Conector indisponível (" + falta + "); usando o modo navegador.");
                    ded = new DedClient(log);
                }
            }
        } catch (Exception e) {
            javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, e.getMessage());
            a.setHeaderText("Não foi possível iniciar o Lançador de Notas");
            a.showAndWait();
            Platform.exit();
            return;
        }

        LoginView login = new LoginView(ded, getHostServices(), this::abrirPrincipal);
        Scene cenaLogin = new Scene(login.getRoot(), 500, 700);
        cenaLogin.getStylesheets().add(getClass().getResource("/estilo.css").toExternalForm());
        stage.setScene(cenaLogin);
        stage.setResizable(true);          // janela normal do Windows: minimizar, maximizar, fechar
        stage.setMinWidth(460);
        stage.setMinHeight(600);
        stage.centerOnScreen();
        stage.show();
        procurarAtualizacao();
    }

    /** Em segundo plano: se houver versão nova no GitHub, baixa e oferece reiniciar. */
    private void procurarAtualizacao() {
        Atualizador.limparRestos();
        java.util.concurrent.CompletableFuture.supplyAsync(Atualizador::verificar).thenAccept(opt -> opt.ifPresent(info -> {
            if (!Atualizador.podeAtualizarSozinho(info)) {
                if (info.versao.equals(Config.avisoReinstalarVisto())) return;
                Platform.runLater(() -> {
                    javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                            "Existe uma versão nova do programa (" + info.versao + ") que precisa ser instalada de novo.\n\n"
                            + "Peça o instalador novo a quem cuida do programa. Enquanto isso, esta versão continua funcionando.");
                    a.initOwner(stage);
                    a.setHeaderText("Versão nova disponível");
                    a.showAndWait();
                    Config.salvarAvisoReinstalarVisto(info.versao);
                });
                return;
            }
            try {
                java.nio.file.Path novo = Atualizador.baixar(info);
                Platform.runLater(() -> oferecerReinicio(info, novo));
            } catch (Exception e) {
                System.err.println("Atualização não baixada: " + e.getMessage());
            }
        }));
    }

    private void oferecerReinicio(Atualizador.Info info, java.nio.file.Path novo) {
        javafx.scene.control.ButtonType agora = new javafx.scene.control.ButtonType("Atualizar agora",
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType depois = new javafx.scene.control.ButtonType("Mais tarde",
                javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        String novidades = info.novidades == null || info.novidades.isBlank() ? "" : "\n\nO que mudou: " + info.novidades;
        javafx.scene.control.Alert a = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.INFORMATION,
                "Chegou uma versão nova do Lançador de Notas (" + info.versao + ")." + novidades
                + "\n\nO programa vai fechar e abrir de novo sozinho. Leva poucos segundos.", agora, depois);
        a.initOwner(stage);
        a.setHeaderText("Atualização pronta");
        if (a.showAndWait().orElse(depois) == agora) {
            try {
                if (ded != null) ded.close();
                Atualizador.aplicarEReiniciar(novo);
            } catch (Exception e) {
                javafx.scene.control.Alert erro = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                        "Não consegui aplicar a atualização: " + e.getMessage());
                erro.showAndWait();
            }
        }
    }

    private void abrirPrincipal(String escola) {
        main = new MainView(ded, stage, getHostServices(), escola);
        // Ocupa a área útil da tela deste computador (sem cobrir a barra de tarefas) e já abre maximizada.
        javafx.geometry.Rectangle2D tela = javafx.stage.Screen.getPrimary().getVisualBounds();
        Scene cena = new Scene(main.getRoot(), tela.getWidth(), tela.getHeight());
        cena.getStylesheets().add(getClass().getResource("/estilo.css").toExternalForm());
        stage.setScene(cena);
        stage.setResizable(true);
        stage.setMinWidth(Math.min(1000, tela.getWidth()));
        stage.setMinHeight(Math.min(600, tela.getHeight()));
        stage.setX(tela.getMinX());
        stage.setY(tela.getMinY());
        stage.setWidth(tela.getWidth());
        stage.setHeight(tela.getHeight());
        stage.setMaximized(true);
        stage.toFront();
        stage.requestFocus();
        main.carregarTurmas();
    }

    @Override
    public void stop() {
        if (ded != null) ded.close();
    }
}
