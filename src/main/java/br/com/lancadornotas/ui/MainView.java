package br.com.lancadornotas.ui;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.ded.Ded;
import br.com.lancadornotas.ded.Ded.ResultadoAluno;
import br.com.lancadornotas.model.*;
import br.com.lancadornotas.model.LinhaNota.Status;
import br.com.lancadornotas.ocr.LeitorFolha;
import br.com.lancadornotas.util.ComparadorNomes;
import br.com.lancadornotas.util.FolhaModelo;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tela principal:
 *   Escola (trocar) · Turma · Divisão (trimestre)  → vêm do DED+
 *   1. foto da folha  2. ler  3. conferir (e ligar cada coluna a uma avaliação)  4. lançar
 *   + "Folha para impressão": modelo com os nomes da turma para o professor só escrever as notas.
 */
public class MainView {

    private static final Pattern ATV_N = Pattern.compile("(?i)\\b(?:atv|ativ\\w*|atividade)\\s*\\.?\\s*(\\d+)");

    private final BorderPane root = new BorderPane();
    private final Ded ded;
    private final Stage stage;
    private final HostServices host;

    private final Label escolaLabel = new Label();
    private final ComboBox<Turma> turma = new ComboBox<>();
    private final ComboBox<String> divisao = new ComboBox<>();
    private final ImageView preview = new ImageView();
    private final Label infoFolha = new Label("Nenhuma foto carregada.");
    private final Button btFoto = new Button("1. Escolher foto da folha");
    private final Button btLer = new Button("2. Ler folha");
    private final Button btLancar = new Button("4. Lançar no DED+");
    private final MenuButton btFolha = new MenuButton("🖨 Imprimir lista da turma");
    private final FlowPane painelColunas = new FlowPane(14, 6);
    private final TableView<LinhaNota> tabela = new TableView<>();
    private final ObservableList<LinhaNota> linhas = FXCollections.observableArrayList();
    private final ObservableList<Aluno> alunos = FXCollections.observableArrayList();
    private final TextArea log = new TextArea();
    private final ProgressBar progresso = new ProgressBar(0);
    private final Label resumo = new Label();

    private String escola;
    private List<Avaliacao> avaliacoes = List.of();
    /** Para cada coluna lida da folha: a avaliação do DED+ onde ela será lançada (null = não lançar). */
    private final List<ComboBox<Avaliacao>> mapeamento = new ArrayList<>();
    private Path fotoAtual;
    private LeituraFolha leitura;
    private boolean ocupado;
    private boolean trocandoTurma;

    public MainView(Ded ded, Stage stage, HostServices host, String escola) {
        this.ded = ded;
        this.stage = stage;
        this.host = host;
        this.escola = escola;
        montar();
    }

    public Parent getRoot() { return root; }

    // ================================================================== layout

    private void montar() {
        escolaLabel.setText(escola != null ? escola : "(escola)");
        escolaLabel.setStyle("-fx-font-weight: bold;");
        Button btTrocar = new Button("Trocar escola");
        btTrocar.setOnAction(e -> trocarEscola());
        HBox linhaEscola = new HBox(10, new Label("Escola:"), escolaLabel, btTrocar);
        linhaEscola.setAlignment(Pos.CENTER_LEFT);

        turma.setPromptText("Escolha a turma");
        turma.setPrefWidth(360);
        turma.setMinWidth(180);
        HBox.setHgrow(turma, Priority.SOMETIMES);
        divisao.setPromptText("Divisão");
        divisao.setPrefWidth(150);
        turma.setOnAction(e -> aoEscolherTurma());
        divisao.setOnAction(e -> aoEscolherDivisao());

        MenuItem fTurma = new MenuItem("Lista desta turma — nomes e avaliações do DED+");
        fTurma.setOnAction(e -> gerarFolha(false));
        fTurma.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(alunos));
        MenuItem fBranco = new MenuItem("Lista em branco (escrever os nomes à mão)");
        fBranco.setOnAction(e -> gerarFolha(true));
        btFolha.getItems().addAll(fTurma, fBranco);

        Button btConfig = new Button("Configurações");
        btConfig.setOnAction(e -> new ConfigDialog(stage).showAndWait());
        Button btAjuda = new Button("? Ajuda");
        btAjuda.setStyle("-fx-font-weight: bold;");
        btAjuda.setOnAction(e -> new AjudaDialog(stage).showAndWait());
        Button btCalibrar = new Button("Calibrar DED");
        btCalibrar.setTooltip(new Tooltip("Mostra o navegador com o inspetor, para ajustar onde o programa clica."));
        btCalibrar.setOnAction(e -> ded.calibrar().whenComplete((ok, ex) ->
                Platform.runLater(() -> log(ex == null ? "Calibração encerrada." : "Erro: " + msg(ex)))));

        Region esp = new Region();
        HBox.setHgrow(esp, Priority.ALWAYS);
        HBox linhaTurma = new HBox(10, new Label("Turma:"), turma, new Label("Divisão:"), divisao, esp,
                btFolha, btAjuda, btConfig);
        if (!ded.usaExtensao()) linhaTurma.getChildren().add(btCalibrar);
        linhaTurma.setAlignment(Pos.CENTER_LEFT);
        VBox topo = new VBox(8, linhaEscola, linhaTurma);
        topo.setPadding(new Insets(10));
        root.setTop(topo);

        // ----- esquerda: foto
        preview.setPreserveRatio(true);
        preview.setFitWidth(270);
        btFoto.setMaxWidth(Double.MAX_VALUE);
        btLer.setMaxWidth(Double.MAX_VALUE);
        btLer.setDisable(true);
        btFoto.setOnAction(e -> escolherFoto());
        btLer.setOnAction(e -> lerFolha());
        infoFolha.setWrapText(true);
        ScrollPane sp = new ScrollPane(preview);
        sp.setPrefViewportWidth(280);
        VBox.setVgrow(sp, Priority.ALWAYS);
        VBox esquerda = new VBox(8, btFoto, btLer, infoFolha, sp);
        esquerda.setPadding(new Insets(0, 10, 10, 10));
        esquerda.setPrefWidth(300);
        esquerda.setMinWidth(240);
        root.setLeft(esquerda);

        // ----- centro
        tabela.setItems(linhas);
        tabela.setEditable(true);
        // a coluna "Observação" ocupa o espaço que sobrar na largura da janela
        tabela.widthProperty().addListener((o, a, n) -> ajustarObservacao());
        tabela.setPlaceholder(new Label("Escolha turma e divisão, carregue a foto e clique em \"Ler folha\"."));
        tabela.setRowFactory(tv -> criarLinhaTabela());
        Label titCol = new Label("Cada coluna da folha vai para qual avaliação do DED+?");
        titCol.getStyleClass().add("titulo-secao");
        Label tit = new Label("3. Confira antes de lançar  (amarelo = conferir · dois cliques para corrigir · botão direito para mais opções)");
        tit.getStyleClass().add("titulo-secao");
        tit.setWrapText(true);
        titCol.setWrapText(true);
        VBox centro = new VBox(6, titCol, painelColunas, new Separator(), tit, tabela, resumo);
        // as ligações coluna→avaliação quebram linha conforme a largura da janela
        painelColunas.prefWrapLengthProperty().bind(centro.widthProperty().subtract(20));
        VBox.setVgrow(tabela, Priority.ALWAYS);
        centro.setPadding(new Insets(0, 10, 10, 0));
        root.setCenter(centro);

        // ----- rodapé
        log.setEditable(false);
        log.setPrefRowCount(4);
        log.setMinHeight(70);
        btLancar.getStyleClass().add("botao-principal");
        btLancar.setPrefHeight(40);
        btLancar.setPrefWidth(220);
        btLancar.setOnAction(e -> lancar());
        progresso.setPrefWidth(220);
        VBox acao = new VBox(6, btLancar, progresso);
        acao.setAlignment(Pos.CENTER);
        HBox.setHgrow(log, Priority.ALWAYS);
        HBox rodape = new HBox(10, log, acao);
        rodape.setPadding(new Insets(0, 10, 10, 10));
        root.setBottom(rodape);

        montarColunasTabela();
        atualizarResumo();
    }

    private TableRow<LinhaNota> criarLinhaTabela() {
        TableRow<LinhaNota> row = new TableRow<>() {
            @Override
            protected void updateItem(LinhaNota item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeIf(c -> c.startsWith("status-"));
                if (item != null && !empty) getStyleClass().add("status-" + item.getStatus().name().toLowerCase());
            }
        };
        MenuItem ok = new MenuItem("Marcar como conferido");
        ok.setOnAction(e -> { row.getItem().setConferidaPeloProfessor(true); recalcular(); });
        MenuItem ign = new MenuItem("Não lançar esta linha");
        ign.setOnAction(e -> { row.getItem().setIgnoradaPeloProfessor(true); recalcular(); });
        ContextMenu menu = new ContextMenu(ok, ign);
        row.contextMenuProperty().bind(javafx.beans.binding.Bindings.when(row.emptyProperty())
                .then((ContextMenu) null).otherwise(menu));
        return row;
    }

    /** As colunas de nota da tabela dependem de quantas colunas a folha tem. */
    @SuppressWarnings("unchecked")
    private void montarColunasTabela() {
        tabela.getColumns().clear();

        TableColumn<LinhaNota, String> cNum = new TableColumn<>("Nº");
        cNum.setCellValueFactory(d -> new SimpleStringProperty(
                d.getValue().getNumero() == null ? "" : String.valueOf(d.getValue().getNumero())));
        cNum.setPrefWidth(40);

        TableColumn<LinhaNota, String> cLido = new TableColumn<>("Nome na folha");
        cLido.setCellValueFactory(d -> d.getValue().nomeLidoProperty());
        cLido.setPrefWidth(130);

        TableColumn<LinhaNota, Aluno> cAluno = new TableColumn<>("Aluno no DED+");
        cAluno.setCellValueFactory(d -> d.getValue().alunoProperty());
        cAluno.setCellFactory(ComboBoxTableCell.forTableColumn(alunos));
        cAluno.setOnEditCommit(e -> {
            e.getRowValue().setAluno(e.getNewValue());
            e.getRowValue().setConferidaPeloProfessor(true);
            recalcular();
        });
        cAluno.setPrefWidth(280);
        tabela.getColumns().addAll(cNum, cLido, cAluno);

        int nCols = leitura == null ? 0 : leitura.colunas.size();
        for (int i = 0; i < nCols; i++) {
            final int c = i;
            LeituraFolha.Coluna col = leitura.colunas.get(i);
            TableColumn<LinhaNota, String> tc = new TableColumn<>(col.nome);
            tc.setCellValueFactory(d -> d.getValue().notaProperty(c));
            tc.setCellFactory(TextFieldTableCell.forTableColumn());
            tc.setOnEditCommit(e -> {
                e.getRowValue().notaProperty(c).set(e.getNewValue() == null ? "" : e.getNewValue().trim());
                e.getRowValue().setConferidaPeloProfessor(true);
                recalcular();
            });
            tc.setPrefWidth(62);
            tabela.getColumns().add(tc);
        }

        TableColumn<LinhaNota, String> cConf = new TableColumn<>("Leitura");
        cConf.setCellValueFactory(d -> new SimpleStringProperty(Math.round(d.getValue().getConfianca() * 100) + "%"));
        cConf.setPrefWidth(60);
        TableColumn<LinhaNota, Status> cStatus = new TableColumn<>("Situação");
        cStatus.setCellValueFactory(d -> d.getValue().statusProperty());
        cStatus.setPrefWidth(90);
        TableColumn<LinhaNota, String> cMsg = new TableColumn<>("Observação");
        cMsg.setCellValueFactory(d -> d.getValue().mensagemProperty());
        cMsg.setPrefWidth(320);
        tabela.getColumns().addAll(cConf, cStatus, cMsg);
        colObservacao = cMsg;
        Platform.runLater(this::ajustarObservacao);
    }

    private TableColumn<LinhaNota, ?> colObservacao;

    private void ajustarObservacao() {
        if (colObservacao == null || tabela.getWidth() <= 0) return;
        double usado = 0;
        for (TableColumn<LinhaNota, ?> c : tabela.getColumns()) if (c != colObservacao) usado += c.getWidth();
        colObservacao.setPrefWidth(Math.max(180, tabela.getWidth() - usado - 20));
    }

    // ================================================================== escola / turma / divisão

    public void carregarTurmas() {
        setOcupado(true);
        ded.listarTurmas().whenComplete((lista, ex) -> Platform.runLater(() -> {
            setOcupado(false);
            if (ex != null) { erro("Não consegui listar as turmas", ex); return; }
            trocandoTurma = true;
            turma.getItems().setAll(lista);
            turma.setValue(null);
            divisao.getItems().clear();
            trocandoTurma = false;
        }));
    }

    private void trocarEscola() {
        if (ocupado) return;
        setOcupado(true);
        log("Abrindo a seleção de escolas...");
        ded.trocarEscola().whenComplete((lista, ex) -> Platform.runLater(() -> {
            setOcupado(false);
            if (ex != null) { erro("Não consegui trocar de escola", ex); return; }
            Escola atual = lista.stream().filter(e -> e.codigo().equals(Config.ultimaEscola())).findFirst().orElse(lista.get(0));
            ChoiceDialog<Escola> d = new ChoiceDialog<>(atual, lista);
            d.initOwner(stage);
            d.setTitle("Trocar escola");
            d.setHeaderText("Em qual escola você vai lançar as notas?");
            d.setContentText("Escola:");
            Escola e = d.showAndWait().orElse(atual);
            Config.salvarUltimaEscola(e.codigo());
            setOcupado(true);
            ded.selecionarEscola(e).whenComplete((esc, ex2) -> Platform.runLater(() -> {
                setOcupado(false);
                if (ex2 != null) { erro("Não consegui abrir a escola", ex2); return; }
                escola = esc;
                escolaLabel.setText(esc);
                alunos.clear();
                avaliacoes = List.of();
                refazerMapeamento();
                casarNomes();
                carregarTurmas();
            }));
        }));
    }

    private void aoEscolherTurma() {
        Turma t = turma.getValue();
        if (t == null || ocupado || trocandoTurma) return;
        trocandoTurma = true;
        divisao.getItems().clear();
        trocandoTurma = false;
        alunos.clear();
        avaliacoes = List.of();
        setOcupado(true);
        log("Abrindo " + t + "...");
        ded.abrirTurma(t).whenComplete((divs, ex) -> Platform.runLater(() -> {
            setOcupado(false);
            if (ex != null) { erro("Não consegui abrir a turma", ex); return; }
            trocandoTurma = true;
            divisao.getItems().setAll(divs);
            trocandoTurma = false;
            if (!divs.isEmpty()) divisao.setValue(divs.get(0)); // a que o DED+ já mostra (dispara aoEscolherDivisao)
        }));
    }

    private void aoEscolherDivisao() {
        String d = divisao.getValue();
        if (d == null || ocupado || trocandoTurma) return;
        setOcupado(true);
        log("Carregando avaliações e alunos de " + d + "...");
        ded.listarAvaliacoes(d)
           .thenCompose(avs -> { avaliacoes = avs; return ded.listarAlunos(d); })
           .whenComplete((lista, ex) -> Platform.runLater(() -> {
               setOcupado(false);
               if (ex != null) { erro("Não consegui carregar a divisão", ex); return; }
               alunos.setAll(lista);
               if (lista.isEmpty()) {
                   log("⚠ Não consegui ler a lista de alunos desta turma no DED+.");
                   Alert a = new Alert(Alert.AlertType.WARNING,
                           "Não consegui ler a lista de alunos desta turma no DED+.\n\n"
                           + "Avise quem cuida do programa: os detalhes ficaram salvos na pasta de capturas.", ButtonType.OK);
                   a.initOwner(stage);
                   a.setHeaderText("Alunos não encontrados");
                   a.show();
               }
               log(avaliacoes.size() + " avaliação(ões): " + avaliacoes.stream().map(Avaliacao::nome).toList());
               if (!lista.isEmpty()) {
                   log("✓ " + lista.size() + " aluno(s) do DED+. Para imprimir a lista com os nomes e as avaliações: "
                       + "botão \"🖨 Imprimir lista da turma\".");
               }
               if (avaliacoes.isEmpty()) {
                   log("⚠ Esta divisão não tem avaliações cadastradas. Cadastre no DED+ primeiro (botão \"Cadastrar avaliação\").");
               }
               verificarCodigoModelo();
               refazerMapeamento();
               casarNomes();
           }));
    }

    // ================================================================== folha para impressão

    private void gerarFolha(boolean emBranco) {
        try {
            String codEscola = codigoEscola();
            FolhaModelo.Dados dados = emBranco
                    ? new FolhaModelo.Dados(nomeEscola(), codEscola, "", "", List.of(), List.of())
                    : dadosAtuais();
            Path pasta = Config.PASTA.resolve("folhas");
            Files.createDirectories(pasta);
            String nome = emBranco ? "folha-em-branco"
                    : (turma.getValue().rotulo() + " " + divisao.getValue()).replaceAll("[^\\p{L}\\p{N}]+", "-");
            Path arq = pasta.resolve(nome + ".html");
            Files.writeString(arq, FolhaModelo.html(dados));
            host.showDocument(arq.toUri().toString());
            log("Folha gerada: " + arq + " — abriu no navegador; use Imprimir (ou Salvar como PDF).");
        } catch (Exception ex) {
            erro("Não consegui gerar a folha", ex);
        }
    }

    private FolhaModelo.Dados dadosAtuais() {
        return new FolhaModelo.Dados(nomeEscola(), codigoEscola(),
                turma.getValue() == null ? "" : turma.getValue().rotulo(),
                divisao.getValue() == null ? "" : divisao.getValue(),
                avaliacoes, new ArrayList<>(alunos));
    }

    private String codigoEscola() {
        Matcher m = Pattern.compile("\\b(\\d{5,8})\\b").matcher(escola == null ? "" : escola);
        return m.find() ? m.group(1) : Config.ultimaEscola();
    }

    private String nomeEscola() {
        return escola == null ? "" : escola.replaceFirst("^\\s*\\d{5,8}\\s*-\\s*", "").replaceFirst("\\s*-\\s*SRE.*$", "");
    }

    /** A folha modelo traz o código da turma+divisão: se não bater com o que está escolhido, avisa. */
    private boolean folhaDoModeloConfere() {
        return leitura != null && leitura.ehModeloDoPrograma()
                && turma.getValue() != null && divisao.getValue() != null
                && FolhaModelo.codigo(dadosAtuais()).equalsIgnoreCase(leitura.codigoModelo.trim());
    }

    private void verificarCodigoModelo() {
        if (leitura == null || !leitura.ehModeloDoPrograma() || "LND-BRANCO".equalsIgnoreCase(leitura.codigoModelo.trim())) return;
        if (turma.getValue() == null || divisao.getValue() == null) {
            log("Folha modelo " + leitura.codigoModelo + " — escolha a turma e a divisão dela.");
        } else if (!folhaDoModeloConfere()) {
            log("⚠ A folha tem o código " + leitura.codigoModelo + ", que NÃO é desta turma/divisão ("
                    + FolhaModelo.codigo(dadosAtuais()) + "). Confira se escolheu a turma certa.");
        } else {
            log("✓ Folha modelo confere com a turma e a divisão escolhidas.");
        }
    }

    // ================================================================== foto e leitura

    private void escolherFoto() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Foto da folha de notas");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagens", "*.jpg", "*.jpeg", "*.png", "*.JPG", "*.JPEG", "*.PNG"));
        File f = fc.showOpenDialog(stage);
        if (f == null) return;
        fotoAtual = f.toPath();
        // carrega já reduzida (foto de celular tem 12+ megapixels e deixaria a tela lenta)
        preview.setImage(new Image(f.toURI().toString(), 900, 0, true, true, true));
        infoFolha.setText(f.getName());
        btLer.setDisable(false);
    }

    private void lerFolha() {
        if (fotoAtual == null) return;
        setOcupado(true);
        log("Lendo a folha (10 a 40 segundos)...");
        List<String> nomes = alunos.stream().map(Aluno::nome).toList();
        CompletableFuture.supplyAsync(() -> {
            try { return LeitorFolha.criar().ler(fotoAtual, nomes); }
            catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
        }).whenComplete((l, ex) -> Platform.runLater(() -> {
            setOcupado(false);
            if (ex != null) { erro("Falha ao ler a folha", ex); return; }
            leitura = l;
            mostrarLeitura();
        }));
    }

    private void mostrarLeitura() {
        StringBuilder sb = new StringBuilder();
        if (leitura.ehModeloDoPrograma()) sb.append("Folha modelo: ").append(leitura.codigoModelo).append('\n');
        if (!leitura.turma.isBlank()) sb.append("Turma na folha: ").append(leitura.turma).append('\n');
        if (!leitura.divisao.isBlank()) sb.append("Divisão: ").append(leitura.divisao).append('\n');
        sb.append("Colunas: ");
        for (LeituraFolha.Coluna c : leitura.colunas) sb.append(c.nome).append(c.valor != null ? " (" + Notas.formatar(c.valor) + ")" : "").append("  ");
        sb.append('\n').append(leitura.alunos.size()).append(" aluno(s) lido(s).");
        infoFolha.setText(sb.toString());
        for (String obs : leitura.observacoes) log("⚠ Leitura: " + obs);

        linhas.clear();
        for (LeituraFolha.Item it : leitura.alunos) {
            LinhaNota ln = new LinhaNota(it.numero, it.nome, it.notas);
            ln.setConfianca(it.confianca);
            linhas.add(ln);
        }
        log("Folha lida: " + leitura.alunos.size() + " linhas, " + leitura.colunas.size() + " colunas.");

        // Folha manuscrita: sugere a turma pelo que está escrito, se ainda não escolheu.
        if (turma.getValue() == null && !leitura.turma.isBlank() && !turma.getItems().isEmpty()) {
            Map<String, Turma> porRotulo = new LinkedHashMap<>();
            turma.getItems().forEach(t -> porRotulo.put(t.rotulo(), t));
            ComparadorNomes.Resultado r = ComparadorNomes.melhor(leitura.turma, porRotulo.keySet());
            if (r != null && r.pontuacao() >= 0.75) log("Turma parecida com a da folha: " + r.nomeOficial() + " — confira e selecione.");
        }
        verificarCodigoModelo();
        montarColunasTabela();
        refazerMapeamento();
        casarNomes();
    }

    // ================================================================== coluna da folha → avaliação

    /** Padrão: "Atv N" → N-ª avaliação do DED+ (Atv 1 = Produção do trabalho, Atv 2 = Apresentação...). */
    private void refazerMapeamento() {
        painelColunas.getChildren().clear();
        mapeamento.clear();
        if (leitura == null) {
            painelColunas.getChildren().add(new Label("(aparece depois de ler a folha)"));
            return;
        }
        for (int i = 0; i < leitura.colunas.size(); i++) {
            LeituraFolha.Coluna col = leitura.colunas.get(i);
            ComboBox<Avaliacao> cb = new ComboBox<>();
            cb.getItems().add(null);
            cb.getItems().addAll(avaliacoes);
            cb.setButtonCell(celulaAvaliacao());
            cb.setCellFactory(lv -> celulaAvaliacao());
            cb.setPrefWidth(250);
            cb.setValue(avaliacaoPadrao(i, col));
            cb.setOnAction(e -> { validarValores(); recalcular(); });
            mapeamento.add(cb);
            Label nome = new Label(col.nome + (col.valor != null ? " (" + Notas.formatar(col.valor) + ")" : "") + " →");
            nome.setStyle("-fx-font-weight: bold;");
            HBox par = new HBox(6, nome, cb);
            par.setAlignment(Pos.CENTER_LEFT);
            painelColunas.getChildren().add(par);
        }
        validarValores();
    }

    private Avaliacao avaliacaoPadrao(int i, LeituraFolha.Coluna col) {
        boolean temNota = leitura.alunos.stream().anyMatch(a -> i < a.notas.size() && !Notas.vazia(a.notas.get(i)));
        if (!temNota || avaliacoes.isEmpty()) return null;
        Matcher m = ATV_N.matcher(col.nome);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1)) - 1;
            return n >= 0 && n < avaliacoes.size() ? avaliacoes.get(n) : null;
        }
        return null; // Recup., Avalia 1 etc.: o professor escolhe
    }

    private static ListCell<Avaliacao> celulaAvaliacao() {
        return new ListCell<>() {
            @Override protected void updateItem(Avaliacao a, boolean empty) {
                super.updateItem(a, empty);
                setText(empty ? null : a == null ? "— não lançar —" : a.toString());
            }
        };
    }

    private void validarValores() {
        if (leitura == null) return;
        for (int i = 0; i < mapeamento.size(); i++) {
            Avaliacao a = mapeamento.get(i).getValue();
            Double v = leitura.colunas.get(i).valor;
            if (a != null && v != null && a.valor() != null && Math.abs(a.valor() - v) > 0.001) {
                log("⚠ " + leitura.colunas.get(i).nome + " vale " + Notas.formatar(v) + " na folha, mas \""
                        + a.nome() + "\" vale " + Notas.formatar(a.valor()) + " no DED+. Confira a ligação.");
            }
        }
        Set<Avaliacao> usadas = new HashSet<>();
        for (ComboBox<Avaliacao> cb : mapeamento) {
            if (cb.getValue() != null && !usadas.add(cb.getValue())) {
                log("⚠ Duas colunas estão indo para a mesma avaliação \"" + cb.getValue().nome() + "\".");
            }
        }
    }

    private Avaliacao destino(int coluna) {
        return coluna < mapeamento.size() ? mapeamento.get(coluna).getValue() : null;
    }

    // ================================================================== conferência

    /** Liga cada linha da folha a um aluno do DED+ (não mexe nas linhas que o professor já conferiu). */
    private void casarNomes() {
        boolean modelo = folhaDoModeloConfere();
        Map<String, Aluno> porRotulo = new LinkedHashMap<>();
        alunos.forEach(a -> porRotulo.put(a.rotulo(), a));

        for (LinhaNota l : linhas) {
            if (l.getStatus() == Status.LANCADO || l.isConferidaPeloProfessor()) continue;
            l.setAluno(null);
            if (alunos.isEmpty()) { l.setCasamento(false, "escolha turma e divisão"); continue; }
            double pont = 0;

            // Folha do programa: o Nº da linha é a posição do aluno no DED+ (conferido pelo nome impresso).
            if (modelo && l.getNumero() != null && l.getNumero() >= 1 && l.getNumero() <= alunos.size()) {
                Aluno a = alunos.get(l.getNumero() - 1);
                if (ComparadorNomes.similaridade(l.getNomeLido(), a.nome()) >= 0.75) { l.setAluno(a); pont = 1.0; }
            }
            if (l.getAluno() == null) {
                ComparadorNomes.Resultado r = ComparadorNomes.melhor(l.getNomeLido(), porRotulo.keySet());
                if (r != null) { l.setAluno(porRotulo.get(r.nomeOficial())); pont = r.pontuacao(); }
            }

            if (l.getAluno() == null) {
                l.setCasamento(false, "não achei este aluno na turma — escolha manualmente");
            } else if (l.getConfianca() < 0.8) {
                l.setCasamento(false, "letra difícil de ler — confira nome e notas");
            } else if (pont < ComparadorNomes.LIMIAR_AUTOMATICO) {
                l.setCasamento(false, "nome parecido (" + Math.round(pont * 100) + "%) — confira");
            } else {
                l.setCasamento(true, "");
            }
        }
        recalcular();
    }

    /**
     * Situação de cada linha, recalculada do zero (exceto as já lançadas). Ordem das regras:
     * ignorada → sem nota → sem aluno → nota inválida → aluno repetido → conferida → casamento seguro.
     */
    private void recalcular() {
        Map<Aluno, Integer> contagem = new HashMap<>();
        for (LinhaNota l : linhas) {
            if (l.getAluno() != null && !l.isIgnoradaPeloProfessor()) contagem.merge(l.getAluno(), 1, Integer::sum);
        }
        for (LinhaNota l : linhas) {
            if (l.getStatus() == Status.LANCADO) continue;
            String problema;
            if (l.isIgnoradaPeloProfessor()) {
                definir(l, Status.IGNORAR, "ignorada pelo professor");
            } else if (!temAlgoParaLancar(l)) {
                definir(l, Status.IGNORAR, "sem nota nas colunas ligadas ao DED+");
            } else if (l.getAluno() == null) {
                definir(l, Status.CONFERIR, l.getMotivoConferir().isBlank() ? "escolha o aluno" : l.getMotivoConferir());
            } else if ((problema = problemaNasNotas(l)) != null) {
                definir(l, Status.CONFERIR, problema);
            } else if (contagem.getOrDefault(l.getAluno(), 0) > 1) {
                definir(l, Status.CONFERIR, "aluno aparece em mais de uma linha");
            } else if (l.isConferidaPeloProfessor()) {
                definir(l, Status.PRONTO, "conferido pelo professor");
            } else if (l.isCasamentoSeguro()) {
                definir(l, Status.PRONTO, "");
            } else {
                definir(l, Status.CONFERIR, l.getMotivoConferir());
            }
        }
        atualizarResumo();
    }

    private static void definir(LinhaNota l, Status s, String msg) {
        l.setStatus(s);
        l.setMensagem(msg);
    }

    private boolean temAlgoParaLancar(LinhaNota l) {
        for (int c = 0; c < mapeamento.size(); c++) {
            if (destino(c) != null && !Notas.vazia(l.getNota(c))) return true;
        }
        return false;
    }

    private String problemaNasNotas(LinhaNota l) {
        for (int c = 0; c < mapeamento.size(); c++) {
            Avaliacao a = destino(c);
            String n = l.getNota(c);
            if (a == null || Notas.vazia(n) || Notas.naoRealizada(n)) continue;
            Double v = Notas.numero(n);
            String col = leitura.colunas.get(c).nome;
            if (v == null) return "nota de " + col + " não é um número: \"" + n + "\"";
            if (v < 0 || (a.valor() != null && v > a.valor() + 0.001)) {
                return "nota de " + col + " (" + n + ") acima do valor da avaliação (" + Notas.formatar(a.valor()) + ")";
            }
        }
        return null;
    }

    private void atualizarResumo() {
        long pronto = linhas.stream().filter(l -> l.getStatus() == Status.PRONTO).count();
        long conferir = linhas.stream().filter(l -> l.getStatus() == Status.CONFERIR).count();
        long lancado = linhas.stream().filter(l -> l.getStatus() == Status.LANCADO).count();
        long erro = linhas.stream().filter(l -> l.getStatus() == Status.ERRO).count();
        long ign = linhas.stream().filter(l -> l.getStatus() == Status.IGNORAR).count();

        Set<Aluno> naFolha = new HashSet<>();
        linhas.forEach(l -> { if (l.getAluno() != null) naFolha.add(l.getAluno()); });
        long faltando = alunos.stream().filter(a -> !naFolha.contains(a)).count();
        boolean algumDestino = mapeamento.stream().anyMatch(cb -> cb.getValue() != null);

        resumo.setText(String.format("Prontos: %d · Conferir: %d · Lançados: %d · Erro: %d · Não lançar: %d%s",
                pronto, conferir, lancado, erro, ign,
                (!alunos.isEmpty() && !linhas.isEmpty() && faltando > 0)
                        ? " · " + faltando + " aluno(s) da turma não aparecem na folha" : ""));
        btLancar.setDisable(ocupado || pronto == 0 || conferir > 0 || !algumDestino);
        tabela.refresh();
    }

    // ================================================================== lançamento

    private void lancar() {
        List<LinhaNota> prontos = linhas.stream().filter(l -> l.getStatus() == Status.PRONTO).toList();
        String div = divisao.getValue();

        // monta um lote por avaliação: matrícula → nota
        Map<Avaliacao, Map<String, String>> lotes = new LinkedHashMap<>();
        Map<String, LinhaNota> porMatricula = new HashMap<>();
        for (int c = 0; c < mapeamento.size(); c++) {
            Avaliacao a = destino(c);
            if (a == null) continue;
            Map<String, String> lote = lotes.computeIfAbsent(a, k -> new LinkedHashMap<>());
            for (LinhaNota l : prontos) {
                String n = l.getNota(c);
                if (Notas.vazia(n)) continue;
                lote.put(l.getAluno().matricula(), Notas.naoRealizada(n) ? Notas.NAO_REALIZADA : n.trim());
                porMatricula.put(l.getAluno().matricula(), l);
            }
        }
        lotes.values().removeIf(Map::isEmpty);
        if (lotes.isEmpty()) return;

        StringBuilder sb = new StringBuilder("Turma: " + turma.getValue() + "\nDivisão: " + div + "\n\n");
        int total = 0;
        for (var e : lotes.entrySet()) {
            sb.append("• ").append(e.getKey().nome()).append(": ").append(e.getValue().size()).append(" nota(s)\n");
            total += e.getValue().size();
        }
        sb.append("\nNotas que já estiverem no DED+ com valor diferente serão substituídas. "
                + "Depois de salvar, o programa recarrega a página e confere aluno por aluno.");
        Alert conf = new Alert(Alert.AlertType.CONFIRMATION, sb.toString(), ButtonType.OK, ButtonType.CANCEL);
        conf.initOwner(stage);
        conf.setHeaderText("Lançar " + total + " nota(s) no DED+?");
        if (conf.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        setOcupado(true);
        progresso.setProgress(0);
        final int totalF = total;
        final int[] feitos = {0};
        Map<String, List<String>> problemas = new HashMap<>();
        Map<String, List<String>> oks = new HashMap<>();

        CompletableFuture<Void> cadeia = CompletableFuture.completedFuture(null);
        for (var e : lotes.entrySet()) {
            Avaliacao a = e.getKey();
            cadeia = cadeia.thenCompose(x -> ded.lancarNotas(div, a, e.getValue(),
                            mat -> Platform.runLater(() -> progresso.setProgress(++feitos[0] / (double) totalF)))
                    .thenAccept(res -> {
                        for (Map.Entry<String, ResultadoAluno> r : res.entrySet()) {
                            (r.getValue().ok() ? oks : problemas)
                                    .computeIfAbsent(r.getKey(), k -> new ArrayList<>())
                                    .add(abreviar(a.nome()) + ": " + r.getValue().mensagem());
                        }
                    }));
        }
        cadeia.whenComplete((ok, ex) -> Platform.runLater(() -> {
            setOcupado(false);
            progresso.setProgress(1);
            for (var e : porMatricula.entrySet()) {
                LinhaNota l = e.getValue();
                List<String> p = problemas.get(e.getKey());
                List<String> o = oks.getOrDefault(e.getKey(), List.of());
                if (p != null) { l.setStatus(Status.ERRO); l.setMensagem(String.join(" | ", p)); }
                else if (!o.isEmpty()) { l.setStatus(Status.LANCADO); l.setMensagem(String.join(" | ", o)); }
                else if (ex != null) { l.setStatus(Status.ERRO); l.setMensagem("interrompido — confira no DED+"); }
            }
            atualizarResumo();
            if (ex != null) erro("O lançamento foi interrompido", ex);
            else log("Concluído: " + oks.size() + " aluno(s) conferidos no DED+, " + problemas.size() + " com problema.");
        }));
    }

    private static String abreviar(String s) {
        return s.length() <= 18 ? s : s.substring(0, 17) + "…";
    }

    // ================================================================== util

    private void setOcupado(boolean sim) {
        ocupado = sim;
        turma.setDisable(sim);
        divisao.setDisable(sim);
        btFoto.setDisable(sim);
        btFolha.setDisable(sim);
        btLer.setDisable(sim || fotoAtual == null);
        root.setCursor(sim ? Cursor.WAIT : Cursor.DEFAULT);
        // barra animada enquanto espera o DED+/leitura: mostra que o programa não travou
        progresso.setProgress(sim ? ProgressBar.INDETERMINATE_PROGRESS : 0);
        atualizarResumo();
    }

    public void log(String msg) {
        log.appendText("[" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + msg + "\n");
    }

    private void erro(String titulo, Throwable ex) {
        log("ERRO: " + titulo + " — " + msg(ex));
        Alert a = new Alert(Alert.AlertType.ERROR, msg(ex), ButtonType.OK);
        a.initOwner(stage);
        a.setHeaderText(titulo);
        a.getDialogPane().setMinWidth(480);
        a.showAndWait();
    }

    private static String msg(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException
                || t.getMessage() == null || t.getClass() == RuntimeException.class)) {
            t = t.getCause();
        }
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }
}
