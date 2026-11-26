package br.com.lancadornotas.ui;

import br.com.lancadornotas.licenca.Licenca;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Janela "Assinatura": mostra o e-mail do DED+, os planos e espera o Mercado Pago confirmar. */
public class AssinaturaDialog {

    private static final NumberFormat REAIS = NumberFormat.getCurrencyInstance(Locale.of("pt", "BR"));
    static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(ZoneId.systemDefault());

    private final Stage janela = new Stage();
    private final Label situacao = new Label();
    private Timeline espera;
    private long validoAntes = 0;

    public AssinaturaDialog(Stage dono, HostServices host, String email, Consumer<Licenca.Estado> aoMudar) {
        janela.initOwner(dono);
        janela.initModality(Modality.WINDOW_MODAL);
        janela.setTitle("Assinatura do Lançador de Notas");

        Licenca.Estado e = Licenca.ultimo();
        Label titulo = new Label("Assinatura do Lançador de Notas");
        titulo.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        Label lEmail = new Label(email != null ? "E-mail do DED+:  " + email : "E-mail do DED+: (não encontrado)");
        lEmail.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label explica = new Label("A assinatura fica ligada a ESTE e-mail do DED+. É pessoal: funciona só para quem entra no DED+ "
                + "com este e-mail (em até 2 computadores). Pagamento pelo Mercado Pago: Pix, cartão ou boleto.");
        explica.setStyle("-fx-text-fill: #444;");
        explica.setWrapText(true);

        situacao.setStyle("-fx-font-size: 15px;");
        situacao.setWrapText(true);
        mostrar(e);

        double pm = e != null && e.precoMensal() > 0 ? e.precoMensal() : 19.90;
        double pa = e != null && e.precoAnual() > 0 ? e.precoAnual() : 149.90;
        Button mensal = plano("Mensal", REAIS.format(pm) + " por mês", "1 mês de uso");
        Button anual = plano("Anual", REAIS.format(pa) + " por ano", "sai " + REAIS.format(pa / 12) + " por mês");
        anual.setStyle(anual.getStyle() + "-fx-base: #2e7d32;");
        HBox planos = new HBox(16, mensal, anual);
        planos.setAlignment(Pos.CENTER);

        Button fechar = new Button("Fechar");
        fechar.setOnAction(x -> janela.close());
        Button jaPaguei = new Button("Já paguei — conferir agora");
        jaPaguei.setOnAction(x -> conferir(email, aoMudar));
        HBox rodape = new HBox(10, jaPaguei, fechar);
        rodape.setAlignment(Pos.CENTER_RIGHT);

        boolean semEmail = email == null;
        mensal.setDisable(semEmail);
        anual.setDisable(semEmail);
        mensal.setOnAction(x -> pagar(host, email, "mensal", aoMudar));
        anual.setOnAction(x -> pagar(host, email, "anual", aoMudar));

        VBox raiz = new VBox(14, titulo, lEmail, explica, planos, situacao, rodape);
        raiz.setPadding(new Insets(22));
        janela.setScene(new Scene(raiz, 640, 430));
        janela.setOnHidden(x -> { if (espera != null) espera.stop(); });
    }

    public void mostrar() { janela.show(); }

    private static Button plano(String nome, String preco, String detalhe) {
        Button b = new Button(nome + "\n" + preco + "\n" + detalhe);
        b.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-alignment: center;");
        b.setPrefSize(280, 110);
        b.setWrapText(true);
        return b;
    }

    private void mostrar(Licenca.Estado e) {
        if (e == null) { situacao.setText(""); return; }
        if (e.ativo()) {
            situacao.setText("✓ Assinatura ATIVA até " + DATA.format(Instant.ofEpochMilli(e.validoAte()))
                    + ". Se pagar de novo, o tempo é somado.");
            situacao.setStyle("-fx-font-size: 15px; -fx-text-fill: #2e7d32; -fx-font-weight: bold;");
        } else {
            situacao.setText(e.mensagem() != null ? e.mensagem() : "Ainda sem assinatura ativa para este e-mail.");
            situacao.setStyle("-fx-font-size: 15px; -fx-text-fill: #b71c1c;");
        }
    }

    private void pagar(HostServices host, String email, String plano, Consumer<Licenca.Estado> aoMudar) {
        validoAntes = Licenca.ultimo() != null && Licenca.ultimo().ativo() ? Licenca.ultimo().validoAte() : 0;
        situacao.setText("Abrindo o Mercado Pago...");
        situacao.setStyle("-fx-font-size: 15px;");
        CompletableFuture.supplyAsync(() -> {
            try { return Licenca.criarPagamento(email, plano); } catch (Exception ex) { throw new RuntimeException(ex.getMessage(), ex); }
        }).whenComplete((link, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                situacao.setText("Não consegui abrir o pagamento: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                situacao.setStyle("-fx-font-size: 15px; -fx-text-fill: #b71c1c;");
                return;
            }
            host.showDocument(link);
            situacao.setText("Pague na página do Mercado Pago que abriu no navegador.\n"
                    + "Esta janela libera sozinha quando o pagamento for confirmado (Pix e cartão: na hora).");
            situacao.setStyle("-fx-font-size: 15px; -fx-text-fill: #1565c0;");
            if (espera != null) espera.stop();
            espera = new Timeline(new KeyFrame(Duration.seconds(6), x -> conferir(email, aoMudar)));
            espera.setCycleCount(150); // ~15 minutos
            espera.play();
        }));
    }

    private void conferir(String email, Consumer<Licenca.Estado> aoMudar) {
        CompletableFuture.supplyAsync(() -> Licenca.consultar(email)).thenAccept(e -> Platform.runLater(() -> {
            aoMudar.accept(e);
            boolean esperandoPagamento = espera != null && espera.getStatus() == javafx.animation.Animation.Status.RUNNING;
            if (e.ativo() && (!esperandoPagamento || e.validoAte() > validoAntes + 60_000)) {
                if (espera != null) espera.stop();
                mostrar(e);
                situacao.setText("✓ Pagamento confirmado! Assinatura ativa até " + DATA.format(Instant.ofEpochMilli(e.validoAte()))
                        + ". Pode fechar esta janela.");
            } else if (!esperandoPagamento) {
                mostrar(e);
            }
        }));
    }
}
