package br.com.lancadornotas.ded;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.model.Aluno;
import br.com.lancadornotas.model.Avaliacao;
import br.com.lancadornotas.model.Escola;
import br.com.lancadornotas.model.Notas;
import br.com.lancadornotas.model.Turma;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Modo CONECTOR (padrão): o professor usa o DED+ no próprio navegador, logado como sempre,
 * e a extensão "Lançador de Notas — Conector" faz o trabalho dentro da página já aberta.
 * Nada de navegador separado, nada de porta de depuração: o DED+ vê um navegador comum.
 */
public class DedExtensao implements Ded {

    private final ConectorExtensao conector;
    private final Consumer<String> log;
    private final ExecutorService fila = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ded-conector"); t.setDaemon(true); return t;
    });
    private List<Aluno> alunosDaTurma = List.of();

    public DedExtensao(Consumer<String> logTela) throws Exception {
        this.log = msg -> { logTela.accept(msg); gravarRegistro(msg); };
        String versao = ExtensaoArquivos.instalar(Config.tokenExtensao());
        conector = new ConectorExtensao(Config.tokenExtensao(), versao, log);
    }

    @Override public boolean usaExtensao() { return true; }
    @Override public boolean conectorConectado() { return conector.conectado(); }
    @Override public String problemaConector() {
        if (conector.senhaErrada() || conector.desatualizado()) {
            return "O conector precisa ser atualizado: abra chrome://extensions, confira se o "
                 + "\"Lançador de Notas — Conector\" está LIGADO e clique no botão ↻ (recarregar) dele. "
                 + "Depois aperte F5 no DED+ (com as turmas na tela).";
        }
        return null;
    }

    // ------------------------------------------------------------------ util

    private <T> CompletableFuture<T> tarefa(Callable<T> c) {
        CompletableFuture<T> f = new CompletableFuture<>();
        fila.submit(() -> {
            try { f.complete(c.call()); } catch (Throwable t) {
                Throwable causa = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
                gravarRegistro("ERRO: " + causa.getMessage());
                guardarPagina("erro");
                f.completeExceptionally(causa);
            }
        });
        return f;
    }

    private JsonNode ordem(String cmd, Map<String, Object> args, long timeoutMs) throws Exception {
        try {
            return conector.enviar(cmd, args, timeoutMs).get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ex ? ex : e;
        }
    }

    private JsonNode estado() throws Exception { return ordem("estado", Map.of(), 8_000); }

    private static boolean logado(String tela) {
        return !tela.equals("login") && !tela.equals("unidades");
    }

    private ResultadoLogin resultadoDoEstado(JsonNode e) throws Exception {
        String tela = e.path("tela").asText();
        if (tela.equals("unidades")) return new ResultadoLogin(escolas(), null);
        if (logado(tela) && e.path("url").asText().contains("dedmais")) {
            return new ResultadoLogin(List.of(), e.path("escola").isNull() ? null : e.path("escola").asText());
        }
        return null;
    }

    private List<Escola> escolas() throws Exception {
        List<Escola> r = new ArrayList<>();
        for (JsonNode n : ordem("listarEscolas", Map.of(), 10_000)) {
            r.add(new Escola(n.path("indice").asInt(), n.path("codigo").asText(), n.path("nome").asText()));
        }
        return r;
    }

    /** Espera a página chegar numa tela (a extensão reinicia quando a página recarrega). */
    private JsonNode esperarTela(Set<String> telas, long ms) throws Exception {
        long limite = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < limite) {
            if (conector.conectado()) {
                try {
                    JsonNode e = estado();
                    if (telas.contains(e.path("tela").asText())) return e;
                } catch (Exception ignored) { /* página carregando */ }
            }
            Thread.sleep(700);
        }
        throw new DedException(DedException.Tipo.OUTRO, "O DED+ não chegou na tela esperada. Confira a janela do DED+.");
    }

    // ------------------------------------------------------------------ login e escola

    @Override
    public CompletableFuture<ResultadoLogin> usarDedJaAberto() {
        return tarefa(() -> {
            long limite = System.currentTimeMillis() + 4_000;
            while (!conector.conectado() && System.currentTimeMillis() < limite) Thread.sleep(250);
            if (!conector.conectado()) return null;
            ResultadoLogin r = resultadoDoEstado(estado());
            if (r != null) log.accept("Conectado ao DED+ aberto no navegador.");
            return r;
        });
    }

    @Override
    public CompletableFuture<ResultadoLogin> entrar() {
        return tarefa(() -> {
            long limite = System.currentTimeMillis() + 30 * 60_000;
            while (System.currentTimeMillis() < limite) {
                if (conector.conectado()) {
                    try {
                        ResultadoLogin r = resultadoDoEstado(estado());
                        if (r != null) { log.accept("Conectado ao DED+ aberto no navegador."); return r; }
                    } catch (Exception ignored) { /* página carregando */ }
                }
                Thread.sleep(1_000);
            }
            throw new DedException(DedException.Tipo.OUTRO, "Não encontrei o DED+ aberto e logado no navegador.");
        });
    }

    @Override public void continuarAgora() { /* o modo conector percebe sozinho */ }

    @Override
    public CompletableFuture<String> selecionarEscola(Escola e) {
        return tarefa(() -> {
            ordem("escolherEscola", Map.of("indice", e.indice()), 10_000);
            JsonNode est = esperarTela(Set.of("inicio", "visao", "avaliacoes", "notas", "outra"), 60_000);
            String esc = est.path("escola").isNull() ? e.toString() : est.path("escola").asText();
            log.accept("Escola: " + esc);
            return esc;
        });
    }

    @Override
    public CompletableFuture<List<Escola>> trocarEscola() {
        return tarefa(() -> {
            ordem("trocarUnidade", Map.of(), 10_000);
            esperarTela(Set.of("unidades"), 30_000);
            return escolas();
        });
    }

    // ------------------------------------------------------------------ turmas, divisões, avaliações

    /** Se a página precisou recarregar, espera a tela inicial e repete a ordem uma vez. */
    private JsonNode ordemComRecarga(String cmd, Map<String, Object> args, long timeout) throws Exception {
        JsonNode r = ordem(cmd, args, timeout);
        if (r.path("recarregando").asBoolean(false)) {
            log.accept("O DED+ está recarregando...");
            esperarTela(Set.of("inicio"), 60_000);
            r = ordem(cmd, args, timeout);
        }
        return r;
    }

    @Override
    public CompletableFuture<List<Turma>> listarTurmas() {
        return tarefa(() -> {
            JsonNode r = ordemComRecarga("listarTurmas", Map.of(), 30_000);
            List<Turma> t = new ArrayList<>();
            int i = 0;
            for (JsonNode n : r.path("turmas")) t.add(new Turma(i++, n.asText()));
            log.accept(t.size() + " turma(s).");
            return t;
        });
    }

    @Override
    public CompletableFuture<List<String>> abrirTurma(Turma t) {
        return tarefa(() -> {
            log.accept("Abrindo " + t + "...");
            JsonNode r = ordemComRecarga("abrirTurma", Map.of("rotulo", t.rotulo()), 90_000);
            List<Aluno> alunos = new ArrayList<>();
            for (JsonNode n : r.path("alunos")) alunos.add(new Aluno(n.path("matricula").asText(), n.path("nome").asText()));
            alunosDaTurma = alunos;
            if (!alunos.isEmpty()) log.accept("Visão Geral: " + alunos.size() + " aluno(s).");
            else salvarHtml("visao-geral-ultima.html", r.path("htmlVisao").asText(""));
            List<String> divs = new ArrayList<>();
            for (JsonNode n : r.path("divisoes")) divs.add(n.asText());
            log.accept("Divisões: " + divs);
            return divs;
        });
    }

    @Override
    public CompletableFuture<List<Avaliacao>> listarAvaliacoes(String divisao) {
        return tarefa(() -> {
            List<Avaliacao> r = new ArrayList<>();
            for (JsonNode n : ordem("listarAvaliacoes", Map.of("divisao", divisao), 40_000)) {
                r.add(new Avaliacao(n.path("indice").asInt(), n.path("nome").asText(),
                        n.path("valor").isNumber() ? n.path("valor").asDouble() : null));
            }
            return r;
        });
    }

    @Override
    public CompletableFuture<List<Aluno>> listarAlunos(String divisao) {
        return tarefa(() -> {
            if (!alunosDaTurma.isEmpty()) {
                log.accept(alunosDaTurma.size() + " aluno(s) na turma.");
                return alunosDaTurma;
            }
            List<Aluno> r = new ArrayList<>();
            JsonNode res = ordem("lerAlunosNotas", Map.of("divisao", divisao), 60_000);
            for (JsonNode n : res.path("alunos")) {
                r.add(new Aluno(n.path("matricula").asText(), n.path("nome").asText()));
            }
            salvarHtml("notas-ultima.html", res.path("html").asText(""));
            log.accept(r.size() + " aluno(s) lidos na tela de notas.");
            alunosDaTurma = r;
            return r;
        });
    }

    // ------------------------------------------------------------------ lançamento

    @Override
    public CompletableFuture<Map<String, ResultadoAluno>> lancarNotas(String divisao, Avaliacao av,
                                                                    Map<String, String> notas, Consumer<String> progresso) {
        return tarefa(() -> {
            log.accept("Lançando \"" + av.nome() + "\"...");
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("divisao", divisao);
            args.put("avaliacao", av.nome());
            Map<String, String> n = new LinkedHashMap<>();
            notas.forEach((k, v) -> n.put(k, Notas.naoRealizada(v) ? Notas.NAO_REALIZADA : v));
            args.put("notas", n);
            JsonNode r = ordem("lancar", args, 8 * 60_000);
            Map<String, ResultadoAluno> res = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = r.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                res.put(e.getKey(), new ResultadoAluno(e.getValue().path("ok").asBoolean(), e.getValue().path("mensagem").asText()));
                progresso.accept(e.getKey());
            }
            long ok = res.values().stream().filter(ResultadoAluno::ok).count();
            log.accept("\"" + av.nome() + "\": " + ok + "/" + res.size() + " conferidos no DED+.");
            return res;
        });
    }

    @Override
    public CompletableFuture<Void> calibrar() {
        return CompletableFuture.failedFuture(new DedException(DedException.Tipo.OUTRO,
                "No modo conector não há calibração: o programa usa o DED+ aberto no seu navegador."));
    }

    // ------------------------------------------------------------------ diagnóstico

    private static synchronized void gravarRegistro(String msg) {
        try {
            Files.createDirectories(DedClient.pastaCapturas());
            var f = DedClient.pastaCapturas().resolve("registro.txt");
            String linha = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm:ss")) + "  " + msg + System.lineSeparator();
            Files.writeString(f, linha, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static void salvarHtml(String nome, String html) {
        if (html == null || html.isBlank()) return;
        try {
            Files.createDirectories(DedClient.pastaCapturas());
            Files.writeString(DedClient.pastaCapturas().resolve(nome), html);
        } catch (Exception ignored) {}
    }

    /** Guarda o código da página atual (sem senhas) em capturas/, para diagnóstico. */
    private void guardarPagina(String motivo) {
        try {
            if (!conector.conectado()) return;
            JsonNode p = conector.enviar("pagina", Map.of(), 8_000).get();
            String nome = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + motivo + ".html";
            Files.writeString(DedClient.pastaCapturas().resolve(nome), "<!-- " + p.path("url").asText() + " -->\n" + p.path("html").asText());
        } catch (Exception ignored) {}
    }

    @Override
    public void close() {
        conector.close();
        fila.shutdownNow();
    }
}
