package br.com.lancadornotas;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Atualização automática.
 *
 * O GitHub (repositório royalparafusos/lancador-notas) publica, a cada alteração no código, um
 * "versao.json" e o arquivo do programa (lancador-notas-ded.jar, poucas centenas de KB).
 * Ao abrir, o programa:
 *   1. lê o versao.json (máx. 8 s; sem internet, segue normalmente);
 *   2. se houver versão mais nova, baixa o jar e confere a assinatura SHA-256;
 *   3. avisa o professor e reinicia: um pequeno script troca o arquivo assim que o programa fecha
 *      (no Windows não dá para trocar um arquivo em uso) e abre o programa de novo.
 *
 * As bibliotecas (lancador-libs.jar) NÃO são atualizadas assim. Se a versão nova precisar de
 * bibliotecas diferentes (libsVersao), o programa avisa que é preciso instalar de novo.
 */
public final class Atualizador {

    public static final String REPOSITORIO = "royalparafusos/lancador-notas";
    static final String URL_VERSAO = "https://github.com/" + REPOSITORIO + "/releases/latest/download/versao.json";
    private static final String NOME_JAR = "lancador-notas-ded.jar";

    private Atualizador() {}

    /** Conteúdo do versao.json publicado pelo GitHub Actions. */
    public static class Info {
        public String versao = "";
        public int libsVersao;
        public String sha256 = "";
        public String jar = "";
        public String novidades = "";
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    /** Versão deste programa (vem do manifest do jar). null quando roda fora do instalador (desenvolvimento). */
    public static String versaoAtual() {
        return Atualizador.class.getPackage().getImplementationVersion();
    }

    /** Versão das bibliotecas instaladas (manifest do lancador-libs.jar ao lado do programa). */
    static Integer libsInstaladas() {
        try {
            Path libs = Config.pastaDoPrograma().resolve("lancador-libs.jar");
            if (!Files.exists(libs)) return null;
            try (JarFile j = new JarFile(libs.toFile())) {
                Manifest m = j.getManifest();
                String v = m == null ? null : m.getMainAttributes().getValue("Libs-Versao");
                return v == null ? null : Integer.parseInt(v.trim());
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Há versão nova publicada? (vazio se não há, se estiver sem internet ou em desenvolvimento) */
    public static Optional<Info> verificar() {
        String atual = versaoAtual();
        if (atual == null) return Optional.empty();
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(System.getProperty("lancador.urlVersao", URL_VERSAO)))
                    .timeout(Duration.ofSeconds(8)).header("User-Agent", "LancadorNotas/" + atual).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) return Optional.empty();
            Info info = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(r.body(), Info.class);
            return comparar(info.versao, atual) > 0 ? Optional.of(info) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** true se a versão nova pode ser aplicada só trocando o programa (mesmas bibliotecas). */
    public static boolean podeAtualizarSozinho(Info info) {
        Integer libs = libsInstaladas();
        return libs != null && libs == info.libsVersao && System.getProperty("jpackage.app-path") != null;
    }

    /** Baixa e confere o jar novo. Devolve o arquivo ".novo" pronto para ser aplicado. */
    public static Path baixar(Info info) throws Exception {
        Path destino = Config.pastaDoPrograma().resolve(NOME_JAR + ".novo");
        Path tmp = Config.pastaDoPrograma().resolve(NOME_JAR + ".baixando");
        HttpResponse<InputStream> r = HTTP.send(HttpRequest.newBuilder(URI.create(info.jar))
                .timeout(Duration.ofMinutes(3)).header("User-Agent", "LancadorNotas").GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IllegalStateException("download da atualização falhou (" + r.statusCode() + ")");
        try (InputStream in = r.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        String sha = sha256(tmp);
        if (!sha.equalsIgnoreCase(info.sha256.trim())) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("o arquivo baixado está corrompido (assinatura não confere)");
        }
        // confere se é mesmo o programa (jar com Main-Class)
        try (JarFile j = new JarFile(tmp.toFile())) {
            if (j.getManifest() == null || j.getManifest().getMainAttributes().getValue("Main-Class") == null) {
                throw new IllegalStateException("o arquivo baixado não é o programa");
            }
        }
        Files.move(tmp, destino, StandardCopyOption.REPLACE_EXISTING);
        return destino;
    }

    /**
     * Fecha o programa e, assim que ele fechar, troca o arquivo e abre de novo.
     * Guarda a versão anterior como "lancador-notas-ded.jar.anterior" (para voltar, se precisar).
     */
    public static void aplicarEReiniciar(Path novo) throws Exception {
        Path jar = Config.pastaDoPrograma().resolve(NOME_JAR);
        Path exe = Path.of(System.getProperty("jpackage.app-path"));
        long pid = ProcessHandle.current().pid();
        Path script = Files.createTempFile("atualiza-lancador-", ".cmd");
        String s = String.join("\r\n",
                "@echo off",
                "chcp 65001 >nul",
                "title Atualizando o Lancador de Notas...",
                ":espera",
                "tasklist /FI \"PID eq " + pid + "\" 2>nul | find \"" + pid + "\" >nul",
                "if not errorlevel 1 (",
                "  ping -n 2 127.0.0.1 >nul",
                "  goto espera",
                ")",
                "copy /y \"" + jar + "\" \"" + jar + ".anterior\" >nul",
                "move /y \"" + novo + "\" \"" + jar + "\" >nul",
                "start \"\" \"" + exe + "\"",
                "(goto) 2>nul & del \"%~f0\"",
                "");
        Files.writeString(script, s, StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/c", "start", "\"Atualizando\"", "/min", "cmd.exe", "/c", script.toString())
                .start();
        System.exit(0);
    }

    /** Se uma atualização anterior ficou pela metade, limpa os restos. */
    public static void limparRestos() {
        try {
            Files.deleteIfExists(Config.pastaDoPrograma().resolve(NOME_JAR + ".baixando"));
        } catch (Exception ignored) {}
    }

    static String sha256(Path f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Compara "1.1.12" com "1.1.9" numericamente. */
    static int comparar(String a, String b) {
        String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? numero(x[i]) : 0;
            int q = i < y.length ? numero(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int numero(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
    }
}
