package br.com.lancadornotas.ui;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.ded.DedClient;
import br.com.lancadornotas.ded.Seletores;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

public class ConfigDialog extends Dialog<Void> {

    public ConfigDialog(Window dono) {
        initOwner(dono);
        setTitle("Configurações");
        setHeaderText("Configurações do Lançador de Notas");

        PasswordField chaveGroq = new PasswordField();
        chaveGroq.setPromptText("gsk_...");
        chaveGroq.setText(Config.chaveGroq());
        chaveGroq.setPrefColumnCount(32);

        PasswordField chave = new PasswordField();
        chave.setPromptText("sk-ant-...");
        chave.setText(Config.chaveApiIA());
        chave.setPrefColumnCount(32);

        CheckBox direto = new CheckBox("Entrar direto na última escola escolhida");
        direto.setSelected(Config.entrarDiretoNaUltimaEscola());

        Label pastas = new Label("Seletores do DED+: " + Seletores.ARQUIVO
                + "\nCapturas de erro: " + DedClient.pastaCapturas()
                + "\nFolhas geradas: " + Config.PASTA.resolve("folhas"));
        pastas.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        pastas.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(12);
        g.setPadding(new Insets(16));
        if (Config.chaveVeioDoInstalador()) {
            g.add(new Label("Leitura das fotos:"), 0, 0);
            Label pronta = new Label("✓ Já configurada pelo instalador. Não precisa mexer.");
            pronta.setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;");
            g.add(pronta, 0, 1);
        } else {
            Label lg = new Label("Chave do Groq — leitura GRATUITA (console.groq.com):");
            Label la = new Label("ou Chave da Anthropic — leitura paga, opcional (usada só se não houver chave do Groq):");
            la.setStyle("-fx-font-size: 11px;");
            g.add(new javafx.scene.layout.VBox(4, lg, chaveGroq, la, chave), 0, 1);
        }
        g.add(direto, 0, 2);
        g.add(pastas, 0, 3);
        getDialogPane().setContent(g);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(b -> {
            if (b == ButtonType.OK) {
                if (!Config.chaveVeioDoInstalador()) {
                    Config.salvarChaveGroq(chaveGroq.getText());
                    Config.salvarChaveApiIA(chave.getText());
                }
                Config.setEntrarDiretoNaUltimaEscola(direto.isSelected());
            }
            return null;
        });
    }
}
