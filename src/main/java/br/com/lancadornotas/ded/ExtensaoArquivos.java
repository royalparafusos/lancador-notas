package br.com.lancadornotas.ded;

import br.com.lancadornotas.Config;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Coloca os arquivos da extensão (o "conector") em ~/.lancador-notas-ded/extensao,
 * de onde o professor a instala no navegador ("Carregar sem compactação").
 * A cada versão nova do programa os arquivos são atualizados; a extensão se recarrega sozinha.
 */
public final class ExtensaoArquivos {

    public static final Path PASTA = Config.PASTA.resolve("extensao");
    private static final String[] ARQUIVOS = {"manifest.json", "background.js", "conteudo.js", "icone.png"};

    private ExtensaoArquivos() {}

    /** Copia os arquivos e grava o token. Devolve a versão da extensão que acompanha o programa. */
    public static String instalar(String token) throws Exception {
        Files.createDirectories(PASTA);
        for (String a : ARQUIVOS) {
            try (InputStream in = ExtensaoArquivos.class.getResourceAsStream("/extensao/" + a)) {
                if (in == null) throw new IllegalStateException("arquivo da extensão ausente: " + a);
                Files.copy(in, PASTA.resolve(a), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.writeString(PASTA.resolve("token.js"), "const TOKEN = '" + token + "';\n", StandardCharsets.UTF_8);

        // a versão da extensão acompanha a do programa: assim ela percebe quando há arquivos novos e se recarrega
        String versao = br.com.lancadornotas.Atualizador.versaoAtual();
        if (versao != null && versao.matches("\\d+(\\.\\d+){0,3}")) {
            Path m = PASTA.resolve("manifest.json");
            String txt = Files.readString(m, StandardCharsets.UTF_8)
                    .replaceFirst("\"version\"\\s*:\\s*\"[^\"]*\"", "\"version\": \"" + versao + "\"");
            Files.writeString(m, txt, StandardCharsets.UTF_8);
        }
        return new ObjectMapper().readTree(PASTA.resolve("manifest.json").toFile()).path("version").asText();
    }
}
