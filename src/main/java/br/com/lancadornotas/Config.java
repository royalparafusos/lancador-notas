package br.com.lancadornotas;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Configurações do programa, guardadas em ~/.lancador-notas-ded/config.properties.
 *
 * A SENHA DO DED NUNCA É GRAVADA EM DISCO. Ela fica só na memória enquanto o programa está aberto.
 */
public final class Config {

    public static final Path PASTA = Path.of(System.getProperty("user.home"), ".lancador-notas-ded");
    private static final Path ARQUIVO = PASTA.resolve("config.properties");

    /**
     * Valores padrão vindos do instalador: arquivo "config-padrao.properties" ao lado do programa
     * (ex.: a chave da API, para o professor não precisar configurar nada).
     */
    private static final Properties padrao = new Properties();
    private static final Properties props = new Properties(padrao);

    static {
        try {
            Path arqPadrao = pastaDoPrograma().resolve("config-padrao.properties");
            if (Files.exists(arqPadrao)) {
                try (var r = Files.newBufferedReader(arqPadrao, java.nio.charset.StandardCharsets.UTF_8)) {
                    padrao.load(r);
                }
            }
        } catch (Exception e) {
            System.err.println("Sem configuração padrão do instalador: " + e.getMessage());
        }
        try {
            Files.createDirectories(PASTA);
            if (Files.exists(ARQUIVO)) {
                try (InputStream in = Files.newInputStream(ARQUIVO)) {
                    props.load(in);
                }
            }
        } catch (IOException e) {
            System.err.println("Não foi possível ler a configuração: " + e.getMessage());
        }
        boolean mudou = false;
        for (String k : new String[]{"ia.groq.chave", "ia.chave"}) {
            String v = padrao.getProperty(k, "");
            if (!v.isBlank() && !props.containsKey(k)) { props.setProperty(k, v); mudou = true; }
        }
        if (mudou) salvar();
    }

    private Config() {}

    /** Pasta onde está o .jar do programa (no instalado: ...\\Lancador de Notas\\app). */
    static Path pastaDoPrograma() {
        try {
            Path jar = Path.of(Config.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(jar) ? jar : jar.getParent();
        } catch (Exception e) {
            return Path.of(".");
        }
    }

    public static String avisoReinstalarVisto() {
        return props.getProperty("atualizacao.avisoReinstalar", "");
    }

    public static void salvarAvisoReinstalarVisto(String versao) {
        props.setProperty("atualizacao.avisoReinstalar", versao);
        salvar();
    }

    /** true se a chave da API veio pronta no instalador. */
    public static boolean chaveVeioDoInstalador() {
        return !padrao.getProperty("ia.chave", "").isBlank() || !padrao.getProperty("ia.groq.chave", "").isBlank();
    }

    /** Chave da API da Anthropic usada para ler a foto. Variável de ambiente tem prioridade. */
    public static String chaveApiIA() {
        String env = System.getenv("ANTHROPIC_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String v = props.getProperty("ia.chave", "").trim();
        return v.isEmpty() ? padrao.getProperty("ia.chave", "").trim() : v;
    }

    public static void salvarChaveApiIA(String chave) {
        props.setProperty("ia.chave", chave == null ? "" : chave.trim());
        salvar();
    }

    /** Chave do Groq (leitura GRATUITA das fotos). Começa com "gsk_". */
    public static String chaveGroq() {
        String env = System.getenv("GROQ_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        String v = props.getProperty("ia.groq.chave", "").trim();
        return v.isEmpty() ? padrao.getProperty("ia.groq.chave", "").trim() : v;
    }

    public static void salvarChaveGroq(String chave) {
        props.setProperty("ia.groq.chave", chave == null ? "" : chave.trim());
        salvar();
    }

    public static String modeloGroq() {
        return props.getProperty("ia.groq.modelo", "qwen/qwen3.8-27b");
    }

    public static String modeloIA() {
        return props.getProperty("ia.modelo", "claude-sonnet-5-5");
    }

    public static String urlDed() {
        return props.getProperty("ded.url", "https://dedmais.educacao.mg.gov.br/");
    }

    /**
     * true = minimiza a janela do navegador depois do login (uso normal).
     * false = deixa a janela visível (para acompanhar/depurar).
     * Obs.: o login sempre usa janela real porque o Seg.ID tem verificação Cloudflare Turnstile.
     */
    public static boolean minimizarNavegador() {
        return Boolean.parseBoolean(props.getProperty("ded.minimizar", "true"));
    }

    public static void setMinimizarNavegador(boolean v) {
        props.setProperty("ded.minimizar", String.valueOf(v));
        salvar();
    }

    /** Código da última escola escolhida (para já deixar selecionada). */
    public static String ultimaEscola() {
        return props.getProperty("login.ultimaEscola", "");
    }

    public static void salvarUltimaEscola(String codigo) {
        props.setProperty("login.ultimaEscola", codigo == null ? "" : codigo);
        salvar();
    }

    /** Se true e a última escola estiver na lista, entra nela sem perguntar. */
    public static boolean entrarDiretoNaUltimaEscola() {
        return Boolean.parseBoolean(props.getProperty("login.entrarDireto", "false"));
    }

    public static void setEntrarDiretoNaUltimaEscola(boolean v) {
        props.setProperty("login.entrarDireto", String.valueOf(v));
        salvar();
    }

    /** Pausa entre ações no DED, em milissegundos — evita sobrecarregar o sistema da SEE. */
    public static int pausaEntreAcoesMs() {
        return Integer.parseInt(props.getProperty("ded.pausaMs", "400"));
    }

    public static String ultimoCpf() {
        return props.getProperty("login.ultimoCpf", "");
    }

    public static void salvarUltimoCpf(String cpf) {
        props.setProperty("login.ultimoCpf", cpf == null ? "" : cpf);
        salvar();
    }

    private static synchronized void salvar() {
        try (OutputStream out = Files.newOutputStream(ARQUIVO)) {
            props.store(out, "Lancador de Notas DED+ - configuracoes (a senha do DED nunca e salva aqui)");
        } catch (IOException e) {
            System.err.println("Não foi possível salvar a configuração: " + e.getMessage());
        }
    }
}
