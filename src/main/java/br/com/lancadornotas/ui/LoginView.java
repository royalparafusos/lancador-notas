package br.com.lancadornotas.ui;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.ded.DedClient;
import br.com.lancadornotas.model.Escola;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.function.Consumer;

/**
 * Login em dois passos:
 *   1. O professor entra no DED+ numa janela NORMAL do navegador (o programa não digita nada e
 *      não controla a janela durante o login, por causa da verificação "Confirme que é humano").
 *   2. Escola — a lista vem da "Seleção de Unidades" do Seg.ID; a última escolhida já vem marcada.
 */
public class LoginView {

    private final StackPane root = new StackPane();
    private final VBox passoLogin = new VBox(10);
    private final VBox passoEscola = new VBox(10);

    private final Button abrir = new Button("Abrir o DED+ para entrar");
    private final Button continuar = new Button("Já entrei, continuar");
    private final Label status = new Label();
    private final ProgressIndicator carregando = new ProgressIndicator();

    private final ListView<Escola> escolas = new ListView<>();
    private final CheckBox entrarDireto = new CheckBox("Entrar direto nesta escola das próximas vezes");
    private final Button entrarEscola = new Button("Entrar na escola");
    private final Label statusEscola = new Label();

    private final DedClient ded;
    private final Consumer<String> aoEntrar;

    /** @param aoEntrar recebe o texto da escola (ex.: "082716 - EE PROFESSORA DORA BARBOSA - SRE MONTES CLAROS") */
    public LoginView(DedClient ded, Consumer<String> aoEntrar) {
        this.ded = ded;
        this.aoEntrar = aoEntrar;
        montarLogin();
        montarEscola();
        root.getChildren().addAll(passoEscola, passoLogin);
        passoEscola.setVisible(false);
    }

    public Parent getRoot() { return root; }

    // ------------------------------------------------------------------ passo 1

    private void montarLogin() {
        passoLogin.setPadding(new Insets(28));
        passoLogin.setAlignment(Pos.CENTER_LEFT);

        Label titulo = new Label("Lançador de Notas");
        titulo.setFont(Font.font("System", FontWeight.BOLD, 26));

        Label passos = new Label(
                "1.  Clique no botão azul abaixo.\n\n"
              + "2.  Vai abrir o DED+ numa janela de internet.\n"
              + "     Entre como você sempre faz: CPF, senha e\n"
              + "     o quadradinho \"Confirme que é humano\".\n\n"
              + "3.  Pronto! O programa continua sozinho.");
        passos.setStyle("-fx-font-size: 15px;");
        passos.setWrapText(true);

        abrir.setDefaultButton(true);
        abrir.setMaxWidth(Double.MAX_VALUE);
        abrir.setPrefHeight(48);
        abrir.getStyleClass().add("botao-principal");
        abrir.setOnAction(e -> entrar());

        continuar.setMaxWidth(Double.MAX_VALUE);
        continuar.setVisible(false);
        continuar.setOnAction(e -> {
            status.setText("Conectando ao DED+...");
            ded.continuarAgora();
        });

        carregando.setVisible(false);
        carregando.setPrefSize(30, 30);
        status.setWrapText(true);
        status.setStyle("-fx-font-size: 14px;");

        Label aviso = new Label("O programa não vê nem guarda sua senha: você digita direto no site do DED+.");
        aviso.setWrapText(true);
        aviso.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        passoLogin.getChildren().addAll(titulo, passos, abrir, carregando, status, continuar, aviso);
    }

    private void entrar() {
        abrir.setDisable(true);
        carregando.setVisible(true);
        continuar.setVisible(true);
        status.setStyle("-fx-text-fill: #333; -fx-font-size: 14px;");
        status.setText("Entre no DED+ na janela que abriu. Quando aparecerem as escolas ou as suas turmas, "
                + "o programa continua sozinho. (Se não continuar, clique em \"Já entrei, continuar\".)");
        ded.entrar().whenComplete((r, ex) -> Platform.runLater(() -> {
            carregando.setVisible(false);
            continuar.setVisible(false);
            abrir.setDisable(false);
            if (ex != null) {
                Throwable causa = ex.getCause() != null ? ex.getCause() : ex;
                erro(status, causa.getMessage());
                abrir.setText("Tentar de novo");
                return;
            }
            if (r.precisaEscolherEscola()) mostrarEscolas(r.escolas());
            else aoEntrar.accept(r.escolaAtual());
        }));
    }

    // ------------------------------------------------------------------ passo 2

    private void montarEscola() {
        passoEscola.setPadding(new Insets(28));
        Label titulo = new Label("Escolha a escola");
        titulo.setFont(Font.font("System", FontWeight.BOLD, 20));
        Label sub = new Label("Em qual escola você vai lançar as notas?");
        escolas.setPrefHeight(220);
        escolas.setOnMouseClicked(e -> { if (e.getClickCount() == 2) entrarNaEscola(); });
        entrarDireto.setSelected(Config.entrarDiretoNaUltimaEscola());
        entrarEscola.setDefaultButton(true);
        entrarEscola.setMaxWidth(Double.MAX_VALUE);
        entrarEscola.setOnAction(e -> entrarNaEscola());
        statusEscola.setWrapText(true);
        passoEscola.getChildren().addAll(titulo, sub, escolas, entrarDireto, entrarEscola, statusEscola);
    }

    private void mostrarEscolas(List<Escola> lista) {
        escolas.getItems().setAll(lista);
        Escola ultima = lista.stream().filter(e -> e.codigo().equals(Config.ultimaEscola())).findFirst().orElse(null);
        escolas.getSelectionModel().select(ultima != null ? ultima : lista.get(0));

        if (lista.size() == 1 || (ultima != null && Config.entrarDiretoNaUltimaEscola())) {
            entrarNaEscola();
            return;
        }
        passoLogin.setVisible(false);
        passoEscola.setVisible(true);
    }

    private void entrarNaEscola() {
        Escola e = escolas.getSelectionModel().getSelectedItem();
        if (e == null) return;
        Config.salvarUltimaEscola(e.codigo());
        Config.setEntrarDiretoNaUltimaEscola(entrarDireto.isSelected());
        entrarEscola.setDisable(true);
        escolas.setDisable(true);
        statusEscola.setStyle("-fx-text-fill: #333;");
        statusEscola.setText("Abrindo " + e.nome() + "...");
        ded.selecionarEscola(e).whenComplete((esc, ex) -> Platform.runLater(() -> {
            entrarEscola.setDisable(false);
            escolas.setDisable(false);
            if (ex != null) {
                passoLogin.setVisible(false);
                passoEscola.setVisible(true);
                erro(statusEscola, (ex.getCause() != null ? ex.getCause() : ex).getMessage());
                return;
            }
            aoEntrar.accept(esc);
        }));
    }

    // ------------------------------------------------------------------ util

    private static void erro(Label l, String msg) {
        l.setStyle("-fx-text-fill: #b00020;");
        l.setText(msg);
    }
}
