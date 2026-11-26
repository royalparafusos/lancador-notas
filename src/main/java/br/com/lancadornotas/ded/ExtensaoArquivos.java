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

        // A versão do conector é a do manifest.json (sobe só quando os arquivos da extensão mudam).
        // Não há recarga automática: o Chrome pode DESATIVAR uma extensão "sem compactação" que se recarrega sozinha.
        return new ObjectMapper().readTree(PASTA.resolve("manifest.json").toFile()).path("version").asText();
    }
}
