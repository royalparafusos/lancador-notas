package br.com.lancadornotas.licenca;

import br.com.lancadornotas.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Licença do programa, presa ao e-mail do DED+ do professor.
 *
 * O servidor (Cloudflare) diz se o e-mail pagou e devolve um "comprovante" assinado digitalmente.
 * O programa guarda o comprovante: se a internet cair, ele continua valendo até a data dele.
 * Ninguém consegue fabricar um comprovante: só o servidor tem a chave que assina.
 * A leitura das fotos também passa pelo servidor e só funciona com comprovante válido.
 */
public final class Licenca {

    /** Endereço do servidor de licenças. Vazio = cobrança desligada (o programa funciona sem licença). */
    public static final String SERVIDOR_PADRAO = "";

    /** Chave PÚBLICA que confere os comprovantes (a privada fica só no servidor). */
    private static final String CHAVE_PUBLICA = "MCowBQYDK2VwAyEAISazLbvgRnj2z23dzhTqnr/s2txDNcZpuyB2x0OzSs8=";

    private static final Path ARQUIVO = Config.PASTA.resolve("licenca.txt");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();

    /** Situação da licença como o programa mostra. */
    public record Estado(boolean ativo, String email, long validoAte, String plano, String mensagem,
                         double precoMensal, double precoAnual, boolean offline) {
        public static Estado desligado() { return new Estado(true, null, Long.MAX_VALUE, "livre", null, 0, 0, false); }
    }

    private static volatile Estado ultimo = null;
    private static volatile String comprovante = null;

    private Licenca() {}

    public static String servidor() {
        String s = Config.servidorLicenca();
        return (s == null || s.isBlank() ? SERVIDOR_PADRAO : s).replaceAll("/+$", "");
    }

    /** A cobrança está ligada? (só depois que o servidor estiver no ar) */
    public static boolean ligada() { return !servidor().isBlank(); }

    public static Estado ultimo() { return ultimo; }

    public static boolean ativa() { return !ligada() || (ultimo != null && ultimo.ativo()); }

    /** Identificação deste computador (resumo; não revela nada do computador). */
    public static String maquina() {
        try {
            String base = System.getenv().getOrDefault("COMPUTERNAME", "") + "|" + System.getProperty("user.name") + "|"
                        + System.getProperty("os.name") + "|" + System.getProperty("user.home");
            byte[] h = MessageDigest.getInstance("SHA-256").digest(base.toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (Exception e) { return "desconhecida"; }
    }

    /** Pergunta ao servidor; sem internet, usa o comprovante guardado. Nunca lança exceção. */
    public static Estado consultar(String email) {
        if (!ligada()) return ultimo = Estado.desligado();
        if (email == null || email.isBlank()) {
            return ultimo = new Estado(false, null, 0, null,
                    "Não consegui ler o seu e-mail do DED+. Entre no DED+ e tente de novo.", 0, 0, false);
        }
        email = email.trim().toLowerCase();
        try {
            JsonNode r = post("/licenca", Map.of("email", email, "maquina", maquina()));
            String comp = r.path("comprovante").asText("");
            if (!comp.isEmpty() && conferir(comp, email) != null) {
                comprovante = comp;
                try { Files.createDirectories(Config.PASTA); Files.writeString(ARQUIVO, comp); } catch (Exception ignored) {}
            } else if (!r.path("ativo").asBoolean()) {
                comprovante = null;
                try { Files.deleteIfExists(ARQUIVO); } catch (Exception ignored) {}
            }
            return ultimo = new Estado(r.path("ativo").asBoolean() && comprovante != null, email, r.path("validoAte").asLong(),
                    r.path("plano").asText(null), r.path("mensagem").asText(null),
                    r.path("precoMensal").asDouble(19.9), r.path("precoAnual").asDouble(149.9), false);
        } catch (Exception semInternet) {
            JsonNode d = null;
            try { if (Files.exists(ARQUIVO)) d = conferir(Files.readString(ARQUIVO).trim(), email); } catch (Exception ignored) {}
            if (d != null) {
                comprovante = lerArquivo();
                return ultimo = new Estado(true, email, d.path("licencaAte").asLong(d.path("validoAte").asLong()),
                        null, null, 19.9, 149.9, true);
            }
            return ultimo = new Estado(false, email, 0, null,
                    "Sem conexão com o servidor de licenças. Confira a internet e tente de novo.", 19.9, 149.9, true);
        }
    }

    /** Cria o pagamento no Mercado Pago e devolve o link para pagar. */
    public static String criarPagamento(String email, String plano) throws Exception {
        JsonNode r = post("/pagamento", Map.of("email", email.trim().toLowerCase(), "plano", plano));
        String link = r.path("link").asText("");
        if (link.isEmpty()) throw new IllegalStateException(r.path("erro").asText("Não consegui criar o pagamento."));
        return link;
    }

    /** Cabeçalhos para a leitura das fotos pelo servidor. */
    public static String comprovanteAtual() { return comprovante != null ? comprovante : lerArquivo(); }

    // ------------------------------------------------------------------ interno
    private static String lerArquivo() {
        try { return Files.exists(ARQUIVO) ? Files.readString(ARQUIVO).trim() : null; } catch (Exception e) { return null; }
    }

    private static JsonNode post(String caminho, Map<String, String> corpo) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(servidor() + caminho))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(corpo)))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 500) throw new java.io.IOException("servidor " + resp.statusCode());
        JsonNode r = JSON.readTree(resp.body());
        if (resp.statusCode() >= 400) throw new IllegalStateException(r.path("erro").asText("Erro " + resp.statusCode()));
        return r;
    }

    /** Confere a assinatura do comprovante, o e-mail, o computador e a validade. Devolve os dados ou null. */
    static JsonNode conferir(String token, String email) {
        try {
            String[] p = token.split("\\.");
            if (p.length != 2) return null;
            PublicKey chave = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(CHAVE_PUBLICA)));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(chave);
            s.update(p[0].getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(Base64.getUrlDecoder().decode(p[1]))) return null;
            JsonNode d = JSON.readTree(Base64.getUrlDecoder().decode(p[0]));
            if (!d.path("email").asText().equalsIgnoreCase(email)) return null;
            String maq = d.path("maquina").asText("");
            if (!maq.isEmpty() && !maq.equals(maquina())) return null;
            if (d.path("validoAte").asLong() < System.currentTimeMillis()) return null;
            return d;
        } catch (Exception e) {
            return null;
        }
    }
}
