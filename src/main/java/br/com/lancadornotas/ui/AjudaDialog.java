package br.com.lancadornotas.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Passo a passo em linguagem simples, com letra grande. */
public class AjudaDialog extends Dialog<Void> {

    private static final String[][] PASSOS = {
        {"Escolha a turma e o trimestre", "Nos campos \"Turma\" e \"Divisão\", lá em cima."},
        {"Folha para impressão (opcional)", "Imprime uma folha com os nomes dos alunos. Você só escreve as notas."},
        {"Tire a foto da folha", "Com o celular, de cima, com boa luz, pegando a folha inteira. Passe a foto para o computador."},
        {"Clique em \"1. Escolher foto da folha\"", "Escolha a foto que você passou para o computador."},
        {"Clique em \"2. Ler folha\"", "Espere de 10 a 40 segundos. As notas aparecem na tabela."},
        {"Confira a tabela", "Linhas AMARELAS precisam de atenção. Dê dois cliques na nota ou no nome para corrigir. "
                + "Se estiver certo, clique com o botão direito e escolha \"Marcar como conferido\"."},
        {"Clique em \"4. Lançar no DED+\"", "O programa lança, salva e confere sozinho. Linhas VERDES foram lançadas."},
    };

    public AjudaDialog(Window dono) {
        initOwner(dono);
        setTitle("Como usar");
        setHeaderText("Como lançar as notas — passo a passo");

        VBox lista = new VBox(14);
        lista.setPadding(new Insets(10, 20, 10, 10));
        for (int i = 0; i < PASSOS.length; i++) {
            Label t = new Label((i + 1) + ". " + PASSOS[i][0]);
            t.setStyle("-fx-font-size: 17px; -fx-font-weight: bold;");
            Label d = new Label(PASSOS[i][1]);
            d.setWrapText(true);
            d.setStyle("-fx-font-size: 15px;");
            d.setMaxWidth(620);
            lista.getChildren().add(new VBox(3, t, d));
        }
        Label obs = new Label("Cores da tabela:  BRANCO = pronto   ·   AMARELO = conferir   ·   VERDE = lançado   ·   "
                + "VERMELHO = deu problema   ·   CINZA = não vai lançar");
        obs.setWrapText(true);
        obs.setMaxWidth(620);
        obs.setStyle("-fx-font-size: 14px; -fx-padding: 10 0 0 0;");
        lista.getChildren().add(obs);

        ScrollPane sp = new ScrollPane(lista);
        sp.setFitToWidth(true);
        sp.setPrefSize(680, 560);
        getDialogPane().setContent(sp);
        getDialogPane().getButtonTypes().add(new ButtonType("Entendi", ButtonBar.ButtonData.OK_DONE));
    }
}
