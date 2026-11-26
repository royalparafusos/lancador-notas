package br.com.lancadornotas.ui;

import br.com.lancadornotas.ded.ExtensaoArquivos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Instalar o conector" em poucos cliques: o programa abre o navegador e deixa copiado o que
 * precisa ser colado. O professor só cola, liga uma chave e clica em dois botões.
 * (O Chrome não deixa programa nenhum instalar extensão sozinho — esses cliques são exigência dele.)
 */
public final class AssistenteConector {

    private AssistenteConector() {}

    private record Navegador(String nome, Path exe, String paginaExtensoes) {}

    private static List<Navegador> navegadores() {
        List<Navegador> l = new ArrayList<>();
        String pf = System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files");
        String pf86 = System.getenv().getOrDefault("ProgramFiles(x86)", "C:\\Program Files (x86)");
        String local = System.getenv().getOrDefault("LOCALAPPDATA", System.getProperty("user.home") + "\\AppData\\Local");
        for (String base : new String[]{pf, pf86, local}) {
            l.add(new Navegador("Google Chrome", Path.of(base, "Google", "Chrome", "Application", "chrome.exe"), "chrome://extensions"));
        }
        for (String base : new String[]{pf86, pf}) {
            l.add(new Navegador("Microsoft Edge", Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"), "edge://extensions"));
        }
        return l;
    }

    /** Abre uma janela do navegador (Chrome; se não houver, Edge). Devolve o navegador ou null. */
    private static Navegador abrirNavegador() {
        for (Navegador n : navegadores()) {
            if (!Files.isRegularFile(n.exe())) continue;
            try {
                new ProcessBuilder(n.exe().toString(), "--new-window").start();
                return n;
            } catch (Exception ignored) { /* tenta o próximo */ }
        }
        return null;
    }

    private static void copiar(String texto) {
        ClipboardContent c = new ClipboardContent();
        c.putString(texto);
        Clipboard.getSystemClipboard().setContent(c);
    }

    /** Faz tudo o que dá para fazer sozinho e guia os cliques que faltam (exigência do navegador). */
    public static void iniciar() {
        try { ExtensaoArquivos.instalar(br.com.lancadornotas.Config.tokenExtensao()); } catch (Exception ignored) {}
        Navegador nav = abrirNavegador();
        String pagina = nav != null ? nav.paginaExtensoes() : "chrome://extensions";
        copiar(pagina);

        Label p1 = new Label("1)  No navegador que abriu, clique na barra de endereço (lá em cima),\n"
                + "      aperte  Ctrl + V  e depois  Enter.   (já copiei o endereço " + pagina + ")");
        Button bt1 = new Button("Copiar " + pagina + " de novo");
        bt1.setOnAction(e -> copiar(pagina));
        Label p2 = new Label("2)  Ligue a chave  \"Modo do desenvolvedor\"  (canto de cima, à direita).");
        Label p3 = new Label("3)  Clique no botão  \"Carregar sem compactação\"  (em cima, à esquerda).");
        Label p4 = new Label("4)  Clique no botão verde abaixo. Na janela que o navegador abriu,\n"
                + "      aperte  Ctrl + V,  depois  Enter  e clique em  \"Selecionar pasta\".");
        Button bt4 = new Button("Copiar a pasta do conector");
        bt4.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-base: #2e7d32;");
        bt4.setOnAction(e -> { copiar(ExtensaoArquivos.PASTA.toString()); bt4.setText("Copiado! Agora Ctrl + V na janela do navegador"); });
        Label fim = new Label("Pronto! Aparece o cartão \"Lançador de Notas — Conector\". Depois é só abrir o DED+\n"
                + "e entrar normalmente. Isso é feito UMA vez só: as melhorias seguintes chegam sozinhas.");
        for (Label l : List.of(p1, p2, p3, p4, fim)) { l.setStyle("-fx-font-size: 15px;"); l.setWrapText(true); }
        fim.setStyle("-fx-font-size: 14px; -fx-text-fill: #2e7d32;");
        Label pasta = new Label("Pasta do conector: " + ExtensaoArquivos.PASTA);
        pasta.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
        pasta.setWrapText(true);

        Alert a = new Alert(Alert.AlertType.INFORMATION, "", ButtonType.CLOSE);
        a.setTitle("Instalar o conector");
        a.setHeaderText(nav != null ? "Abri o " + nav.nome() + ". Faça estes 4 passos (só na primeira vez):"
                                    : "Abra o Google Chrome e faça estes 4 passos (só na primeira vez):");
        a.getDialogPane().setContent(new VBox(12, p1, bt1, p2, p3, p4, bt4, fim, pasta));
        a.getDialogPane().setPrefWidth(660);
        a.initModality(javafx.stage.Modality.NONE);
        a.show();
    }
}
