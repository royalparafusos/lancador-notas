package br.com.lancadornotas.ded;

import br.com.lancadornotas.Config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Seletores e rótulos do DED+, em ~/.lancador-notas-ded/ded-seletores.properties.
 * Se a versão do arquivo do usuário for mais antiga que a do programa, ele é atualizado
 * (o antigo é guardado como .bak).
 */
public final class Seletores {

    public static final Path ARQUIVO = Config.PASTA.resolve("ded-seletores.properties");
    private final Properties props = new Properties();

    public Seletores() {
        try {
            Properties padrao = carregarPadrao();
            int vPadrao = Integer.parseInt(padrao.getProperty("versao", "1").trim());

            boolean copiar = !Files.exists(ARQUIVO);
            if (!copiar) {
                Properties atual = ler(ARQUIVO);
                int vAtual = Integer.parseInt(atual.getProperty("versao", "1").trim());
                if (vAtual < vPadrao) {
                    Files.copy(ARQUIVO, ARQUIVO.resolveSibling("ded-seletores.properties.bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                    copiar = true;
                }
            }
            if (copiar) {
                try (InputStream in = Seletores.class.getResourceAsStream("/ded-seletores.properties")) {
                    Files.copy(in, ARQUIVO, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            props.putAll(padrao);          // chaves novas sempre existem
            props.putAll(ler(ARQUIVO));    // o arquivo do usuário tem prioridade
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível carregar os seletores do DED: " + e.getMessage(), e);
        }
    }

    private static Properties carregarPadrao() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Seletores.class.getResourceAsStream("/ded-seletores.properties");
             Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static Properties ler(Path arq) throws IOException {
        Properties p = new Properties();
        try (Reader r = new InputStreamReader(Files.newInputStream(arq), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    /** Alternativas separadas por "||", em ordem de preferência. */
    public List<String> get(String chave) {
        return Arrays.stream(texto(chave).split("\\|\\|")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Valor bruto (usado para os rótulos/regex). */
    public String texto(String chave) {
        String v = props.getProperty(chave);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("'" + chave + "' não configurado em " + ARQUIVO);
        }
        return v.trim();
    }
}
