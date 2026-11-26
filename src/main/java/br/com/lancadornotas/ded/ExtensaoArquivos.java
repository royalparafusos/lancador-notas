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

    /**
     * Versão MÍNIMA do conector que este programa aceita. O conector da loja pode demorar umas horas
     * para chegar (revisão da loja + atualização do navegador), então o programa não exige a versão
     * mais nova — só sobe este número quando o programa depender de algo que só existe no conector novo.
     */
    public static final String VERSAO_MINIMA = "1.4.0";

    /** Endereços do conector nas lojas (vazio = ainda não publicado; usa a instalação pela pasta). */
    public static final String LOJA_CHROME = "";
    public static final String LOJA_EDGE = "";

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
        new ObjectMapper().readTree(PASTA.resolve("manifest.json").toFile()); // confere que o manifest é válido
        return VERSAO_MINIMA;
    }
}
