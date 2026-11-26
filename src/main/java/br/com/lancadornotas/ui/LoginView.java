package br.com.lancadornotas.ui;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.ded.Ded;
import br.com.lancadornotas.ded.ExtensaoArquivos;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.util.Duration;
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

    private final Ded ded;
    private final HostServices host;
    private final Consumer<String> aoEntrar;

    /** @param aoEntrar recebe o texto da escola (ex.: "082716 - EE PROFESSORA DORA BARBOSA - SRE MONTES CLAROS") */
    public LoginView(Ded ded, HostServices host, Consumer<String> aoEntrar) {
        this.ded = ded;
        this.host = host;
        this.aoEntrar = aoEntrar;
        if (ded.usaExtensao()) montarLoginConector(); else montarLogin();
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
              + "3.  Pronto! O programa continua sozinho.\n\n"
              + "Dica: deixe a janela do DED+ aberta. Na próxima vez\n"
              + "que abrir o programa, ele entra direto, sem novo login.");
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

        // Se o DED+ já estiver aberto e logado na janela do programa, entra direto.
        abrir.setDisable(true);
        carregando.setVisible(true);
        status.setText("Procurando um DED+ já aberto...");
        ded.usarDedJaAberto().whenComplete((r, ex) -> Platform.runLater(() -> {
            abrir.setDisable(false);
            carregando.setVisible(false);
            status.setText("");
            if (ex == null && r != null) {
                if (r.precisaEscolherEscola()) mostrarEscolas(r.escolas());
                else aoEntrar.accept(r.escolaAtual());
            }
        }));
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

    // ------------------------------------------------------------------ passo 1 (modo conector)

    private final VBox instalar = new VBox(8);
    private Timeline vigia;

    /**
     * Modo conector: o professor entra no DED+ no PRÓPRIO navegador. O programa só espera a extensão
     * avisar que o DED+ está aberto e logado. Se a extensão não aparecer, mostra como instalar.
     */
    private void montarLoginConector() {
        passoLogin.setPadding(new Insets(24));
        passoLogin.setAlignment(Pos.TOP_LEFT);

        Label titulo = new Label("Lançador de Notas");
        titulo.setFont(Font.font("System", FontWeight.BOLD, 26));
        Label passos = new Label(
                "1.  Abra o DED+ no seu navegador (Chrome ou Edge)\n"
              + "     e entre como você sempre faz.\n\n"
              + "2.  Pronto! Com o DED+ aberto, o programa se conecta sozinho.");
        passos.setStyle("-fx-font-size: 15px;");
        passos.setWrapText(true);

        abrir.setText("Abrir o DED+ no navegador");
        abrir.setMaxWidth(Double.MAX_VALUE);
        abrir.setPrefHeight(46);
        abrir.getStyleClass().add("botao-principal");
        abrir.setOnAction(e -> host.showDocument(br.com.lancadornotas.Config.urlDed()));

        status.setWrapText(true);
        status.setStyle("-fx-font-size: 14px;");
        carregando.setPrefSize(28, 28);
        carregando.setVisible(true);

        // ---- instalação do conector (aparece se a extensão não for encontrada)
        Label t = new Label("Primeira vez neste computador? Instale o conector (1 minuto):");
        t.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        t.setWrapText(true);
        Label p = new Label(
                "a)  No navegador, abra o endereço  chrome://extensions\n"
              + "      (no Edge:  edge://extensions)\n"
              + "b)  Ligue a chave \"Modo do desenvolvedor\".\n"
              + "c)  Clique em \"Carregar sem compactação\" e escolha a pasta\n"
              + "      que o botão abaixo mostra (pasta \"extensao\").\n"
              + "d)  Feche e abra o DED+ de novo (botão azul acima).\n"
              + "Atenção: na tela de LOGIN do Seg.ID não aperte F5. Se aparecer\n"
              + "\"404 j_security_check\", abra o DED+ de novo pelo botão azul.");
        p.setStyle("-fx-font-size: 13px;");
        p.setWrapText(true);
        Button copiar = new Button("Copiar o endereço chrome://extensions");
        copiar.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString("chrome://extensions");
            Clipboard.getSystemClipboard().setContent(c);
            copiar.setText("Copiado! Cole na barra de endereço do navegador");
        });
        Button pasta = new Button("Abrir a pasta do conector");
        pasta.setOnAction(e -> host.showDocument(ExtensaoArquivos.PASTA.toUri().toString()));
        Label caminho = new Label(ExtensaoArquivos.PASTA.toString());
        caminho.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        caminho.setWrapText(true);
        if (!ExtensaoArquivos.LOJA_CHROME.isEmpty() || !ExtensaoArquivos.LOJA_EDGE.isEmpty()) {
            // conector publicado na loja: 1 clique e o navegador atualiza sozinho
            t.setText("Primeira vez neste computador? Instale o conector (1 clique):");
            p.setText("Clique no botão do seu navegador, depois em \"Usar no Chrome\" (ou \"Obter\" no Edge)\n"
                    + "e confirme em \"Adicionar extensão\". Depois abra o DED+ pelo botão azul acima.\n"
                    + "Atenção: na tela de LOGIN do Seg.ID não aperte F5.");
            instalar.getChildren().addAll(new Separator(), t, p);
            if (!ExtensaoArquivos.LOJA_CHROME.isEmpty()) {
                Button chrome = new Button("Instalar no Google Chrome");
                chrome.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
                chrome.setOnAction(e -> host.showDocument(ExtensaoArquivos.LOJA_CHROME));
                instalar.getChildren().add(chrome);
            }
            if (!ExtensaoArquivos.LOJA_EDGE.isEmpty()) {
                Button edge = new Button("Instalar no Microsoft Edge");
                edge.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
                edge.setOnAction(e -> host.showDocument(ExtensaoArquivos.LOJA_EDGE));
                instalar.getChildren().add(edge);
            }
        } else {
            instalar.getChildren().addAll(new Separator(), t, p, copiar, pasta, caminho);
        }
        instalar.setVisible(false);
        instalar.setManaged(false);

        passoLogin.getChildren().addAll(titulo, passos, abrir, carregando, status, instalar);

        // vigia: atualiza a mensagem e mostra a instalação se a extensão não aparecer
        final long inicio = System.currentTimeMillis();
        vigia = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
            boolean ok = ded.conectorConectado();
            if (ok) {
                status.setStyle("-fx-text-fill: #2e7d32; -fx-font-size: 14px;");
                status.setText("✓ Conector ativo no navegador.");
                instalar.setVisible(false);
                instalar.setManaged(false);
            } else if (ded.problemaConector() != null) {
                status.setStyle("-fx-text-fill: #b00020; -fx-font-size: 14px;");
                status.setText(ded.problemaConector());
            } else {
                status.setStyle("-fx-text-fill: #333; -fx-font-size: 14px;");
                status.setText("Esperando o DED+ aberto no navegador...\n"
                        + "(Se o DED+ já está aberto, clique nele e aperte F5 com as turmas na tela.)");
                if (System.currentTimeMillis() - inicio > 6_000) {
                    instalar.setVisible(true);
                    instalar.setManaged(true);
                }
            }
        }));
        vigia.setCycleCount(Timeline.INDEFINITE);
        vigia.play();

        ded.entrar().whenComplete((r, ex) -> Platform.runLater(() -> {
            vigia.stop();
            carregando.setVisible(false);
            if (ex != null) {
                erro(status, (ex.getCause() != null ? ex.getCause() : ex).getMessage());
                return;
            }
            if (ded.problemaConector() != null) {
                Alert a = new Alert(Alert.AlertType.WARNING, ded.problemaConector(), ButtonType.OK);
                a.setHeaderText("Atualize o conector");
                a.showAndWait();
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
