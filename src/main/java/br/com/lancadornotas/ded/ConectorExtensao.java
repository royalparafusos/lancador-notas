package br.com.lancadornotas.ded;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Servidor local (só 127.0.0.1) por onde a extensão do navegador busca ordens e devolve resultados.
 *
 *   GET  /proximo    → próxima ordem da fila (200 + JSON) ou nada (204). Também marca "conectado".
 *   POST /resposta   → resultado de uma ordem {id, ok, resultado, erro}
 *   POST /progresso  → mensagem de andamento {texto}
 *
 * Toda chamada precisa do cabeçalho X-Token (o token fica na pasta da extensão, gerado pelo programa):
 * assim um site qualquer aberto no navegador não consegue conversar com o programa.
 */
public class ConectorExtensao implements AutoCloseable {

    public static final int PORTA = 47321;

    private final HttpServer servidor;
    private final String token;
    private final String versaoExtensaoEsperada;
    private final Consumer<String> progresso;
    private final ObjectMapper json = new ObjectMapper();
    private final BlockingDeque<Map<String, Object>> fila = new LinkedBlockingDeque<>();
    private final Map<String, CompletableFuture<JsonNode>> pendentes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService relogio = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "conector-relogio"); t.setDaemon(true); return t;
    });

    private volatile long ultimoContato;
    private volatile String urlAtual = "";
    private volatile boolean pediuRecarregar;

    public ConectorExtensao(String token, String versaoExtensaoEsperada, Consumer<String> progresso) throws IOException {
        this.token = token;
        this.versaoExtensaoEsperada = versaoExtensaoEsperada;
        this.progresso = progresso;
        try {
            servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORTA), 0);
        } catch (BindException e) {
            throw new IOException("O Lançador de Notas já está aberto (ou outro programa está usando a porta "
                    + PORTA + "). Feche a outra janela do programa e abra de novo.", e);
        }
        servidor.createContext("/proximo", this::proximo);
        servidor.createContext("/resposta", this::resposta);
        servidor.createContext("/progresso", this::receberProgresso);
        servidor.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "conector-http"); t.setDaemon(true); return t;
        }));
        servidor.start();
    }

    /** A extensão buscou ordens nos últimos 3 segundos? */
    public boolean conectado() {
        return System.currentTimeMillis() - ultimoContato < 3_000;
    }

    public String urlAtual() { return urlAtual; }

    /** Envia uma ordem para a página do DED+ e espera a resposta (até timeoutMs). */
    public CompletableFuture<JsonNode> enviar(String cmd, Map<String, Object> args, long timeoutMs) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        Map<String, Object> ordem = new LinkedHashMap<>();
        ordem.put("id", id);
        ordem.put("cmd", cmd);
        ordem.put("args", args == null ? Map.of() : args);
        pendentes.put(id, f);
        fila.add(ordem);
        relogio.schedule(() -> {
            if (!f.isDone()) {
                fila.remove(ordem);
                pendentes.remove(id);
                f.completeExceptionally(new DedException(DedException.Tipo.OUTRO, conectado()
                        ? "O DED+ demorou demais para responder (" + cmd + "). Confira a janela do DED+ e tente de novo."
                        : "O DED+ não está aberto no navegador (ou o conector não está instalado/ativo)."));
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);
        return f;
    }

    // ------------------------------------------------------------------ HTTP

    private boolean autorizado(HttpExchange ex) throws IOException {
        String t = ex.getRequestHeaders().getFirst("X-Token");
        if (t == null || !t.equals(token)) {
            responder(ex, 403, "{}");
            return false;
        }
        return true;
    }

    private void proximo(HttpExchange ex) throws IOException {
        if (!autorizado(ex)) return;
        ultimoContato = System.currentTimeMillis();
        Map<String, String> q = parametros(ex.getRequestURI().getRawQuery());
        if (q.containsKey("url")) urlAtual = q.get("url");
        // extensão desatualizada (o programa trouxe arquivos novos): manda ela se recarregar uma vez
        String ext = q.getOrDefault("ext", "");
        if (!pediuRecarregar && !ext.isEmpty() && !ext.equals(versaoExtensaoEsperada)) {
            pediuRecarregar = true;
            responder(ex, 200, "{\"id\":\"-\",\"cmd\":\"__recarregar_extensao\"}");
            return;
        }
        Map<String, Object> ordem = fila.poll();
        if (ordem == null) {
            ex.sendResponseHeaders(204, -1);
            ex.close();
            return;
        }
        responder(ex, 200, json.writeValueAsString(ordem));
    }

    private void resposta(HttpExchange ex) throws IOException {
        if (!autorizado(ex)) return;
        ultimoContato = System.currentTimeMillis();
        JsonNode r = json.readTree(ex.getRequestBody());
        responder(ex, 200, "{}");
        CompletableFuture<JsonNode> f = pendentes.remove(r.path("id").asText());
        if (f == null) return;
        if (r.path("ok").asBoolean(false)) f.complete(r.path("resultado"));
        else f.completeExceptionally(new DedException(DedException.Tipo.OUTRO, r.path("erro").asText("erro no DED+")));
    }

    private void receberProgresso(HttpExchange ex) throws IOException {
        if (!autorizado(ex)) return;
        JsonNode r = json.readTree(ex.getRequestBody());
        responder(ex, 200, "{}");
        String t = r.path("texto").asText("");
        if (!t.isEmpty()) progresso.accept(t);
    }

    private static void responder(HttpExchange ex, int status, String corpo) throws IOException {
        byte[] b = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    private static Map<String, String> parametros(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String par : raw.split("&")) {
            int i = par.indexOf('=');
            if (i > 0) m.put(URLDecoder.decode(par.substring(0, i), StandardCharsets.UTF_8),
                             URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    @Override
    public void close() {
        servidor.stop(0);
        relogio.shutdownNow();
    }
}
