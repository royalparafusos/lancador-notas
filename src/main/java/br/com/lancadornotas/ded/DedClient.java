package br.com.lancadornotas.ded;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.model.*;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.SelectOption;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Opera o Seg.ID + DED+ numa janela de navegador própria do programa.
 *
 * Fluxo real do sistema (conferido com o professor em 25/11/2026):
 *   Seg.ID login (CPF/senha + Cloudflare Turnstile)
 *     → "Seleção de Unidades" (escolhe a escola)
 *     → DED+ início: cartões das turmas
 *     → Avaliações da turma (escolhe a divisão: 1º/2º/3º trimestre) → cartões das avaliações
 *     → avaliacao-detalhe: cartões dos alunos (matrícula, nome, "Nota:", "Não realizada") + botão Salvar
 *
 * LOGIN FEITO PELO PROFESSOR, SEM AUTOMAÇÃO:
 * o Seg.ID tem a verificação "Confirme que é humano" (Cloudflare Turnstile), que bloqueia navegadores
 * controlados por programa. Por isso o programa abre o Edge/Chrome como um navegador NORMAL
 * (sem nenhum controle), o professor digita CPF e senha e clica na verificação ele mesmo,
 * e só DEPOIS que o login terminou o programa se conecta ao navegador para lançar as notas.
 * Nada é disfarçado nem burlado: quem passa pela verificação é a pessoa.
 *
 * O Playwright não é thread-safe: tudo roda numa thread própria; os métodos públicos devolvem
 * CompletableFuture para não travar a tela.
 */
public class DedClient implements AutoCloseable {

    private static final int TIMEOUT_MS = 20_000;

    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ded-navegador");
        t.setDaemon(true);
        return t;
    });

    private final Seletores sel = new Seletores();
    private final Consumer<String> log;

    private Playwright playwright;
    private Browser navegador;        // conexão com o Edge/Chrome aberto pelo programa (depois do login)
    private BrowserContext contexto;
    private Page page;
    private Process processo;         // o Edge/Chrome em si
    private int porta;                // porta local de depuração do navegador
    private volatile boolean conectarAgora; // professor clicou em "Já entrei"

    /** Turma/divisão abertas no momento — usadas para voltar ao lugar certo entre avaliações. */
    private String divisaoAtual;
    /** Alunos lidos da "Visão Geral da Turma" ao abrir a turma (evita abrir uma avaliação só para isso). */
    private List<Aluno> alunosDaTurma = List.of();

    public DedClient(Consumer<String> log) {
        this.log = log;
    }

    public record ResultadoLogin(List<Escola> escolas, String escolaAtual) {
        public boolean precisaEscolherEscola() { return !escolas.isEmpty(); }
    }

    /** Resultado do lançamento de UM aluno numa avaliação. */
    public record ResultadoAluno(boolean ok, String mensagem) {}

    // ================================================================== ciclo de vida

    private static final Set<String> COMPATIVEIS = Set.of("chrome.exe", "msedge.exe", "brave.exe", "vivaldi.exe");

    /**
     * Navegador a usar: o PADRÃO do computador, se for compatível (Chrome, Edge, Brave, Vivaldi);
     * senão Edge (vem em todo Windows) ou Chrome. Firefox não aceita a conexão do programa.
     */
    Path acharNavegador() {
        Path padrao = navegadorPadraoWindows();
        if (padrao != null) {
            if (COMPATIVEIS.contains(padrao.getFileName().toString().toLowerCase(Locale.ROOT)) && Files.isExecutable(padrao)) {
                return padrao;
            }
            log.accept("O navegador padrão (" + padrao.getFileName() + ") não é compatível. Vou usar o Edge ou o Chrome.");
        }
        List<String> c = new ArrayList<>();
        for (String base : new String[]{System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"),
                                        System.getenv("LOCALAPPDATA")}) {
            if (base == null) continue;
            c.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
            c.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        c.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        c.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        c.add("/usr/bin/microsoft-edge");
        c.add("/usr/bin/google-chrome");
        c.add("/usr/bin/chromium");
        for (String s : c) {
            Path p = Path.of(s);
            if (Files.isExecutable(p)) return p;
        }
        return null;
    }

    /** Lê no registro do Windows qual é o navegador padrão para links https. */
    static Path navegadorPadraoWindows() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return null;
        try {
            String progId = regValor("HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice", "/v", "ProgId");
            if (progId == null) return null;
            String cmd = regValor("HKCR\\" + progId + "\\shell\\open\\command", "/ve", null);
            if (cmd == null) cmd = regValor("HKCU\\Software\\Classes\\" + progId + "\\shell\\open\\command", "/ve", null);
            if (cmd == null) return null;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([^\"]+\\.exe)\"|(\\S+\\.exe)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cmd);
            if (!m.find()) return null;
            return Path.of(m.group(1) != null ? m.group(1) : m.group(2));
        } catch (Exception e) {
            return null;
        }
    }

    /** Executa "reg query" e devolve o valor (REG_SZ) pedido. */
    private static String regValor(String chave, String opcao, String nome) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("reg", "query", chave, opcao));
        if (nome != null) cmd.add(nome);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String saida = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        p.waitFor(5, TimeUnit.SECONDS);
        for (String linha : saida.split("\\R")) {
            int i = linha.indexOf("REG_SZ");
            if (i >= 0) return linha.substring(i + "REG_SZ".length()).trim();
        }
        return null;
    }

    private Path perfil() { return Config.PASTA.resolve("perfil-navegador"); }

    /** Porta que o navegador do programa realmente está usando (ele grava no arquivo DevToolsActivePort). */
    private Integer portaAtiva() {
        try {
            Path f = perfil().resolve("DevToolsActivePort");
            if (!Files.exists(f)) return null;
            return Integer.parseInt(Files.readAllLines(f).get(0).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private boolean respondendo(int p) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + p + "/json/version"))
                    .timeout(java.time.Duration.ofSeconds(2)).GET().build();
            return java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Abre o navegador NORMAL (sem automação), num perfil próprio do programa, na página do DED+.
     * Se já houver uma janela do programa aberta, reaproveita (só abre uma aba nova nela).
     */
    private void abrirNavegadorNormal() throws Exception {
        desconectar();
        Integer existente = portaAtiva();
        if (existente != null && respondendo(existente)) {
            porta = existente;
            novaAba(Config.urlDed());
            log.accept("Usando a janela do navegador que já estava aberta.");
            return;
        }
        Path exe = acharNavegador();
        if (exe == null) {
            throw new DedException(DedException.Tipo.OUTRO,
                    "Não encontrei o Microsoft Edge nem o Google Chrome neste computador.");
        }
        Files.createDirectories(perfil());
        Files.deleteIfExists(perfil().resolve("DevToolsActivePort"));
        porta = -1;
        processo = new ProcessBuilder(exe.toString(),
                // porta 0 = o navegador escolhe uma porta livre e grava no arquivo DevToolsActivePort do perfil
                "--remote-debugging-port=0",
                "--remote-debugging-address=127.0.0.1",
                "--user-data-dir=" + perfil(),
                "--no-first-run", "--no-default-browser-check",
                // janela minimizada não pode "dormir", senão as telas do DED não carregam
                "--disable-background-timer-throttling",
                "--disable-renderer-backgrounding",
                "--disable-backgrounding-occluded-windows",
                "--window-size=1100,820",
                "--new-window", Config.urlDed())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        // Espera o navegador ficar pronto. O processo pode terminar logo (o Edge/Chrome às vezes
        // repassa para outro processo) — por isso conferimos a PORTA, não o processo.
        long limite = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < limite) {
            Integer p = portaAtiva();
            if (p != null && respondendo(p)) { porta = p; log.accept("Navegador aberto: " + exe.getFileName()); return; }
            Thread.sleep(500);
        }
        throw new DedException(DedException.Tipo.OUTRO,
                "O navegador abriu, mas não aceitou a ligação com o programa.\n"
                + "Feche TODAS as janelas do " + exe.getFileName().toString().replace(".exe", "")
                + " e clique em \"Tentar de novo\".");
    }

    /** Abre uma aba nova pelo endpoint HTTP do navegador (sem se conectar à página). */
    private void novaAba(String url) {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + porta + "/json/new?"
                            + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8)))
                    .timeout(java.time.Duration.ofSeconds(5)).PUT(java.net.http.HttpRequest.BodyPublishers.noBody()).build();
            java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    /** Lista as abas abertas SEM se conectar a elas (endpoint /json/list do próprio navegador). */
    @SuppressWarnings("unchecked")
    private List<String> urlsAbertas() {
        try {
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + porta + "/json/list"))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            var resp = java.net.http.HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            List<Map<String, Object>> abas = new com.fasterxml.jackson.databind.ObjectMapper().readValue(resp.body(), List.class);
            List<String> urls = new ArrayList<>();
            for (Map<String, Object> a : abas) if ("page".equals(a.get("type"))) urls.add(String.valueOf(a.get("url")));
            return urls;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean ehTelaDeLogin(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("login") || u.contains("sso.") || u.contains("acesso.gov.br") || u.contains("auth");
    }

    private static boolean ehPosLogin(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return !ehTelaDeLogin(u) && (u.contains("dedmais") || u.contains("ssc.prodemge"));
    }

    /** Conecta o programa ao navegador (só chamado depois que o professor entrou). */
    private void conectar() {
        Map<String, String> env = new HashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
        navegador = playwright.chromium().connectOverCDP("http://127.0.0.1:" + porta);
        contexto = navegador.contexts().get(0);
        contexto.setDefaultTimeout(TIMEOUT_MS);
        page = contexto.pages().stream().filter(pg -> ehPosLogin(pg.url())).findFirst()
                .orElse(contexto.pages().isEmpty() ? contexto.newPage() : contexto.pages().get(0));
    }

    /** Solta a conexão sem fechar o navegador (usado se conectou cedo demais). */
    private void desconectar() {
        try { if (playwright != null) playwright.close(); } catch (Exception ignored) {}
        playwright = null; navegador = null; contexto = null; page = null;
    }

    private void fecharNavegador() {
        try { if (navegador != null) navegador.newBrowserCDPSession().send("Browser.close"); } catch (Exception ignored) {}
        desconectar();
        if (processo != null) {
            try {
                processo.descendants().forEach(ProcessHandle::destroy);
                processo.destroy();
            } catch (Exception ignored) {}
            processo = null;
        }
    }

    @Override
    public void close() {
        try { thread.submit(this::fecharNavegador).get(10, TimeUnit.SECONDS); } catch (Exception ignored) {}
        thread.shutdownNow();
    }

    private <T> CompletableFuture<T> naThread(Callable<T> acao) {
        CompletableFuture<T> f = new CompletableFuture<>();
        thread.submit(() -> {
            try {
                if (page == null && !(acao instanceof Login)) {
                    throw new DedException(DedException.Tipo.OUTRO, "Não está conectado ao DED+. Feche e abra o programa para entrar de novo.");
                }
                f.complete(acao.call());
            } catch (Throwable t) {
                if (page != null) capturarTela("erro");
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Marca a tarefa de login (é a única que pode rodar sem navegador conectado). */
    private interface Login<T> extends Callable<T> {}

    // ================================================================== janela

    /**
     * Garante a janela do navegador em estado NORMAL (nunca minimizada): minimizada, o navegador para de
     * desenhar a página e os cliques do programa ficam esperando para sempre. A janela pode ficar atrás
     * do programa — coberta ela continua funcionando (--disable-backgrounding-occluded-windows).
     */
    private void restaurarJanela() {
        janela("normal");
    }

    private void mostrar() {
        janela("normal");
        page.bringToFront();
    }

    private void janela(String estado) {
        try {
            CDPSession cdp = contexto.newCDPSession(page);
            JsonObject w = cdp.send("Browser.getWindowForTarget");
            JsonObject p = new JsonObject();
            p.addProperty("windowId", w.get("windowId").getAsInt());
            JsonObject b = new JsonObject();
            b.addProperty("windowState", estado);
            p.add("bounds", b);
            cdp.send("Browser.setWindowBounds", p);
            cdp.detach();
        } catch (Exception e) {
            // sem minimizar não é grave
        }
    }

    // ================================================================== login e escola

    /**
     * Abre o navegador normal e ESPERA o professor entrar sozinho (até 15 min).
     * Detecta o fim do login pela URL da aba (sem se conectar a ela) ou pelo botão "Já entrei".
     */
    public CompletableFuture<ResultadoLogin> entrar() {
        return naThread((Login<ResultadoLogin>) () -> {
            conectarAgora = false;
            abrirNavegadorNormal();
            log.accept("Entre no DED+ na janela do navegador (CPF, senha e \"Confirme que é humano\").");

            long inicio = System.currentTimeMillis();
            long limite = inicio + 15 * 60_000;
            boolean viuLogin = false;
            long posLoginDesde = 0;
            long semResposta = 0;
            while (System.currentTimeMillis() < limite) {
                if (!respondendo(porta)) {
                    if (semResposta == 0) semResposta = System.currentTimeMillis();
                    if (System.currentTimeMillis() - semResposta > 6_000) {
                        throw new DedException(DedException.Tipo.OUTRO, "A janela do navegador foi fechada antes de terminar o login.");
                    }
                    Thread.sleep(700);
                    continue;
                }
                semResposta = 0;
                List<String> urls = urlsAbertas();
                if (urls.stream().anyMatch(DedClient::ehTelaDeLogin)) { viuLogin = true; posLoginDesde = 0; }
                boolean pos = urls.stream().anyMatch(DedClient::ehPosLogin) && urls.stream().noneMatch(DedClient::ehTelaDeLogin);
                if (pos && posLoginDesde == 0) posLoginDesde = System.currentTimeMillis();
                if (!pos) posLoginDesde = 0;

                // Saiu da tela de login (ou já estava logado: perfil lembrou) e a página assentou por alguns segundos
                long estavel = posLoginDesde == 0 ? 0 : System.currentTimeMillis() - posLoginDesde;
                boolean pronto = pos && (viuLogin ? estavel > 2_500 : estavel > 8_000);
                if (pronto || conectarAgora) {
                    conectar();
                    ResultadoLogin r = detectarPosLogin(conectarAgora ? 15_000 : 6_000);
                    if (r != null) {
                        log.accept(r.precisaEscolherEscola() ? "Login ok. Escolha a escola." : "Login ok: " + r.escolaAtual());
                        restaurarJanela();
                        return r;
                    }
                    desconectar(); // ainda não terminou: solta o navegador e continua esperando
                    conectarAgora = false;
                    posLoginDesde = 0;
                }
                Thread.sleep(700);
            }
            throw new DedException(DedException.Tipo.OUTRO, "O login não foi concluído em 15 minutos.");
        });
    }

    /** Botão "Já entrei, continuar" da tela de login. */
    public void continuarAgora() { conectarAgora = true; }

    /** Retorna null se ainda não saiu da tela de login. */
    private ResultadoLogin detectarPosLogin(int ms) {
        long limite = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < limite) {
            for (Page pg : contexto.pages()) {
                if (ehPosLogin(pg.url())) { page = pg; break; }
            }
            if (existe("unidades.titulo", 200)) {
                page.waitForTimeout(800);
                return new ResultadoLogin(lerEscolas(), null);
            }
            if (page.url().contains("dedmais") && marcarCartoes("turma", 0) > 0) {
                return new ResultadoLogin(List.of(), escolaNoCabecalho());
            }
            page.waitForTimeout(400);
        }
        return null;
    }

    private List<Escola> lerEscolas() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> linhas = (List<Map<String, Object>>) page.evaluate(JS_ESCOLAS);
        List<Escola> r = new ArrayList<>();
        for (Map<String, Object> m : linhas) {
            r.add(new Escola(((Number) m.get("i")).intValue(), (String) m.get("codigo"), (String) m.get("nome")));
        }
        if (r.isEmpty()) {
            throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                    "Tela de seleção de escola sem itens reconhecíveis. Captura em " + pastaCapturas());
        }
        return r;
    }

    public CompletableFuture<String> selecionarEscola(Escola e) {
        return naThread(() -> {
            page.evaluate(JS_ESCOLAS); // remarca as linhas
            clicarSeguro(page.locator("[data-lnd-escola='" + e.indice() + "']"));
            long limite = System.currentTimeMillis() + 40_000;
            while (System.currentTimeMillis() < limite) {
                if (page.url().contains("dedmais") && marcarCartoes("turma", 0) > 0) {
                    String esc = escolaNoCabecalho();
                    log.accept("Escola: " + (esc != null ? esc : e.toString()));
                    restaurarJanela();
                    return esc != null ? esc : e.toString();
                }
                page.waitForTimeout(500);
            }
            throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                    "A escola foi escolhida mas o DED+ não abriu as turmas. Captura em " + pastaCapturas());
        });
    }

    /** Botão "Trocar Unidade" do DED+ → volta para a lista de escolas. */
    public CompletableFuture<List<Escola>> trocarEscola() {
        return naThread(() -> {
            irParaInicio();
            clicar("ded.trocarUnidade");
            if (!existe("unidades.titulo", 30_000)) {
                throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO, "Não abriu a seleção de unidades.");
            }
            page.waitForTimeout(800);
            return lerEscolas();
        });
    }

    private String escolaNoCabecalho() {
        Object t = page.evaluate("() => { const m = document.body.innerText.match(/\\b\\d{6}\\s*-\\s*[^\\n]+/); return m ? m[0].trim() : null; }");
        return t == null ? null : t.toString();
    }

    // ================================================================== turmas / divisões / avaliações

    public CompletableFuture<List<Turma>> listarTurmas() {
        return naThread(() -> {
            irParaInicio();
            List<Turma> r = new ArrayList<>();
            for (Map<String, Object> m : infoCartoes("turma")) {
                r.add(new Turma(((Number) m.get("i")).intValue(), rotuloTurma((String) m.get("texto"))));
            }
            log.accept(r.size() + " turma(s).");
            return r;
        });
    }

    /** Abre a turma, entra em Avaliações e devolve as divisões (ex.: "3º Trimestre") — a selecionada vem primeiro. */
    public CompletableFuture<List<String>> abrirTurma(Turma t) {
        return naThread(() -> {
            irParaInicio();
            // acha a turma pelo NOME (a posição na lista pode mudar)
            Integer idx = null;
            for (Map<String, Object> m : infoCartoes("turma")) {
                if (rotuloTurma((String) m.get("texto")).equals(t.rotulo())) { idx = ((Number) m.get("i")).intValue(); break; }
            }
            if (idx == null) {
                throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                        "Não achei a turma \"" + t.rotulo() + "\" na tela inicial do DED+. Feche e abra o programa.");
            }
            log.accept("Abrindo a turma no DED+...");
            clicarSeguro(page.locator("[data-lnd-turma='" + idx + "']"));
            esperarCarregar();
            alunosDaTurma = List.of();
            if (!naPaginaAvaliacoes(1_500)) {
                alunosDaTurma = lerAlunosVisaoGeral();
                log.accept("Entrando em Avaliações...");
                entrarEmAvaliacoes();
            }
            List<String> opcoes = lerDivisoes();
            log.accept("Divisões: " + opcoes);
            return opcoes;
        });
    }

    public CompletableFuture<List<Avaliacao>> listarAvaliacoes(String divisao) {
        return naThread(() -> {
            garantirAvaliacoes(divisao);
            return lerAvaliacoes();
        });
    }

    /** Lista os alunos abrindo a primeira avaliação (todas têm os mesmos alunos) e volta. */
    public CompletableFuture<List<Aluno>> listarAlunos(String divisao) {
        return naThread(() -> {
            if (!alunosDaTurma.isEmpty()) {
                log.accept(alunosDaTurma.size() + " aluno(s) na turma.");
                return alunosDaTurma;
            }
            // plano B: abre a primeira avaliação e lê de lá
            garantirAvaliacoes(divisao);
            if (lerAvaliacoes().isEmpty()) return List.of();
            abrirAvaliacao(0);
            List<Aluno> r = new ArrayList<>();
            for (Map<String, Object> m : lerCartoesAlunos()) {
                r.add(new Aluno((String) m.get("matricula"), (String) m.get("nome")));
            }
            voltarParaAvaliacoes(divisao);
            log.accept(r.size() + " aluno(s) na turma.");
            return r;
        });
    }

    // ================================================================== lançamento

    /**
     * Lança as notas de UMA avaliação, salva, recarrega a página e confere aluno por aluno.
     *
     * @param notas matrícula → nota ("8,5") ou "NR" (não realizada)
     */
    public CompletableFuture<Map<String, ResultadoAluno>> lancarNotas(String divisao, Avaliacao av,
                                                                      Map<String, String> notas,
                                                                      Consumer<String> progresso) {
        return naThread(() -> {
            garantirAvaliacoes(divisao);
            abrirAvaliacao(av.indice());
            log.accept("Lançando \"" + av.nome() + "\"...");

            Map<String, ResultadoAluno> res = new LinkedHashMap<>();
            Map<String, Integer> idxPorMatricula = new HashMap<>();
            Map<String, String> valorAtual = new HashMap<>();
            for (Map<String, Object> m : lerCartoesAlunos()) {
                idxPorMatricula.put((String) m.get("matricula"), ((Number) m.get("i")).intValue());
                valorAtual.put((String) m.get("matricula"), (String) m.get("valor"));
            }

            Map<String, String> alterados = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : notas.entrySet()) {
                String mat = e.getKey();
                String nota = e.getValue();
                Integer i = idxPorMatricula.get(mat);
                if (i == null) { res.put(mat, new ResultadoAluno(false, "aluno não está na tela desta avaliação")); continue; }

                if (Notas.naoRealizada(nota)) {
                    if (!marcarNaoRealizada(i)) {
                        res.put(mat, new ResultadoAluno(false, "não achei a opção \"Não realizada\""));
                        continue;
                    }
                    alterados.put(mat, nota);
                } else {
                    Double v = Notas.numero(nota);
                    if (v == null) { res.put(mat, new ResultadoAluno(false, "nota inválida: " + nota)); continue; }
                    String fmt = Notas.formatar(v);
                    if (Notas.iguais(valorAtual.get(mat), fmt)) {
                        res.put(mat, new ResultadoAluno(true, "já estava " + fmt));
                        progresso.accept(mat);
                        continue;
                    }
                    String erro = digitarNota(i, fmt);
                    if (erro != null) { res.put(mat, new ResultadoAluno(false, erro)); continue; }
                    alterados.put(mat, fmt);
                }
                progresso.accept(mat);
                pausa();
            }

            if (alterados.isEmpty()) {
                log.accept("Nada novo para salvar em \"" + av.nome() + "\".");
                voltarParaAvaliacoes(divisao);
                return res;
            }

            clicar("notas.salvar");
            page.waitForTimeout(1_200);
            esperarCarregar();

            // Conferência: recarrega a página e lê o que o DED+ realmente gravou.
            try { page.reload(new Page.ReloadOptions().setTimeout(20_000)); } catch (PlaywrightException ignored) {}
            esperarCarregar();
            Map<String, Map<String, Object>> depois = new HashMap<>();
            for (Map<String, Object> m : lerCartoesAlunos()) depois.put((String) m.get("matricula"), m);

            for (Map.Entry<String, String> e : alterados.entrySet()) {
                Map<String, Object> m = depois.get(e.getKey());
                if (m == null) { res.put(e.getKey(), new ResultadoAluno(false, "não consegui conferir após salvar")); continue; }
                boolean ok = Notas.naoRealizada(e.getValue())
                        ? Boolean.TRUE.equals(m.get("nr"))
                        : Notas.iguais((String) m.get("valor"), e.getValue());
                String antes = valorAtual.get(e.getKey());
                res.put(e.getKey(), ok
                        ? new ResultadoAluno(true, Notas.vazia(antes) ? "lançado" : "alterado de " + antes)
                        : new ResultadoAluno(false, "DED+ mostra \"" + m.get("valor") + "\" após salvar"));
            }
            long ok = res.values().stream().filter(ResultadoAluno::ok).count();
            log.accept("\"" + av.nome() + "\": " + ok + "/" + res.size() + " conferidos no DED+.");
            voltarParaAvaliacoes(divisao);
            return res;
        });
    }

    /** Digita a nota e confere se o campo aceitou. Retorna null se ok, ou a mensagem de erro. */
    private String digitarNota(int i, String fmt) {
        Locator campo = page.locator("[data-lnd-nota='" + i + "']");
        if (campo.count() == 0) return "campo de nota não encontrado";
        campo.focus();
        campo.fill("");
        campo.fill(fmt);
        campo.press("Tab");
        if (Notas.iguais(campo.inputValue(), fmt)) return null;

        // Campo com máscara: digitar só os algarismos ("8,00" → "800")
        campo.focus();
        campo.fill("");
        campo.pressSequentially(fmt.replaceAll("\\D", ""), new Locator.PressSequentiallyOptions().setDelay(40));
        campo.press("Tab");
        if (Notas.iguais(campo.inputValue(), fmt)) return null;
        return "o campo não aceitou " + fmt + " (ficou \"" + campo.inputValue() + "\")";
    }

    private boolean marcarNaoRealizada(int i) {
        Object ok = page.evaluate(JS_MARCAR_NR, Map.of("i", i, "rotulo", sel.texto("rotulo.naoRealizada")));
        if (!Boolean.TRUE.equals(ok)) return false;
        clicarSeguro(page.locator("[data-lnd-nr='" + i + "']"));
        return true;
    }

    // ================================================================== navegação interna

    /** Tela inicial do DED+ (lista de turmas): endereço sem caminho, ou /home, /inicio. */
    private boolean naTelaInicial() {
        try {
            java.net.URI u = java.net.URI.create(page.url());
            String caminho = u.getPath() == null ? "" : u.getPath().replaceAll("/+$", "").toLowerCase(Locale.ROOT);
            return u.getHost() != null && u.getHost().contains("dedmais")
                    && (caminho.isEmpty() || caminho.equals("/home") || caminho.equals("/inicio"));
        } catch (Exception e) {
            return false;
        }
    }

    private void irParaInicio() {
        // Atenção: o cabeçalho da tela de Avaliações também parece um "cartão de turma";
        // por isso confere o ENDEREÇO antes de confiar nos cartões.
        if (naTelaInicial() && marcarCartoes("turma", 1_500) > 0) return;
        if (paginaAvaliacoes != null) voltarDaAbaNova();

        // 1) botão "início" (casinha) do próprio DED+ — navegação interna, rápida
        try {
            Object ok = page.evaluate(JS_BOTAO_INICIO);
            if (Boolean.TRUE.equals(ok)) {
                clicarSeguro(page.locator("[data-lnd-inicio]"));
                if (esperarTelaInicial(8_000)) return;
            }
        } catch (PlaywrightException | DedException ignored) {}

        // 2) "voltar" do navegador, algumas vezes (Avaliações → Visão Geral → início)
        for (int i = 0; i < 4 && !naTelaInicial(); i++) {
            try { page.goBack(new Page.GoBackOptions().setTimeout(8_000)); } catch (PlaywrightException ignored) {}
            esperarCarregar();
        }
        if (esperarTelaInicial(4_000)) return;

        // 3) último recurso: recarregar o site (pode passar pela tela "Processando… Seg.ID")
        log.accept("Recarregando o DED+...");
        page.navigate("https://dedmais.educacao.mg.gov.br/",
                new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
        if (!esperarTelaInicial(45_000)) {
            throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                    "Não encontrei os cartões das turmas no DED+. A sessão pode ter expirado — feche e abra o programa.");
        }
    }

    private boolean esperarTelaInicial(int ms) {
        long limite = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < limite) {
            if (naTelaInicial() && marcarCartoes("turma", 0) > 0) return true;
            page.waitForTimeout(300);
        }
        return false;
    }

    private void voltarDaAbaNova() {
        try { page.close(); } catch (PlaywrightException ignored) {}
        page = paginaAvaliacoes;
        paginaAvaliacoes = null;
    }

    private boolean naPaginaAvaliacoes(int ms) {
        long limite = System.currentTimeMillis() + ms;
        String re = sel.texto("rotulo.avaliacoes.pagina");
        do {
            Object ok = page.evaluate("re => new RegExp(re,'i').test(document.body.textContent)", re);
            if (Boolean.TRUE.equals(ok) && page.url().contains("/avaliacao") && !page.url().contains("detalhe")) return true;
            page.waitForTimeout(300);
        } while (System.currentTimeMillis() < limite);
        return false;
    }

    private void garantirAvaliacoes(String divisao) {
        if (!naPaginaAvaliacoes(1_000)) {
            throw new DedException(DedException.Tipo.OUTRO, "Escolha a turma de novo (o DED+ saiu da tela de avaliações).");
        }
        if (divisao != null && !divisao.equals(divisaoAtual)) {
            selecionarDivisao(divisao);
            esperarCarregar();
            divisaoAtual = divisao;
        }
        marcarCartoes("avaliacao", 8_000);
    }

    // ------------------------------------------------------------------ divisão (1º/2º/3º trimestre)
    // O DED+ pode usar uma lista comum (<select>) ou um menu próprio (botão "3º Trimestre ⌄").
    // Os dois são tratados; no pior caso fica a divisão que o DED+ já está mostrando.

    private static final String RE_DIVISAO = "^\\s*\\d+\\s*[ºª°o]?\\s*(Trimestre|Bimestre|Semestre|Per[ií]odo|Etapa)\\s*$";

    /** Divisões disponíveis; a que está selecionada vem primeiro. */
    @SuppressWarnings("unchecked")
    private List<String> lerDivisoes() {
        divisaoAtual = null;
        Map<String, Object> r = (Map<String, Object>) page.evaluate(JS_DIVISAO, RE_DIVISAO);
        String tipo = (String) r.get("tipo");
        List<String> opcoes = new ArrayList<>((List<String>) r.get("opcoes"));
        String atual = (String) r.get("atual");

        if ("custom".equals(tipo)) {
            // abre o menu, lê as opções novas que aparecerem e fecha
            try {
                clicarSeguro(page.locator("[data-lnd-divisao='gatilho']"));
                page.waitForTimeout(700);
                opcoes = new ArrayList<>((List<String>) page.evaluate(JS_DIVISAO_OPCOES, RE_DIVISAO));
                page.keyboard().press("Escape");
                page.waitForTimeout(300);
            } catch (PlaywrightException e) {
                log.accept("(não consegui abrir a lista de divisões; usando a atual)");
            }
        }
        if (atual != null) {
            opcoes.remove(atual);
            opcoes.add(0, atual);
            divisaoAtual = atual;
        }
        if (opcoes.isEmpty()) {
            opcoes.add("(divisão atual)");
            divisaoAtual = "(divisão atual)";
        }
        return opcoes;
    }

    @SuppressWarnings("unchecked")
    private void selecionarDivisao(String divisao) {
        if (divisao.startsWith("(")) return;
        Map<String, Object> r = (Map<String, Object>) page.evaluate(JS_DIVISAO, RE_DIVISAO);
        if ("select".equals(r.get("tipo"))) {
            page.locator("[data-lnd-divisao='gatilho']").selectOption(new SelectOption().setLabel(divisao));
            return;
        }
        if ("custom".equals(r.get("tipo"))) {
            clicarSeguro(page.locator("[data-lnd-divisao='gatilho']"));
            page.waitForTimeout(700);
            page.evaluate(JS_DIVISAO_OPCOES, RE_DIVISAO); // marca as opções
            Locator op = page.locator("[data-lnd-divisao-opcao]").filter(new Locator.FilterOptions().setHasText(divisao));
            if (op.count() == 0) {
                page.keyboard().press("Escape");
                throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO, "Não achei a divisão \"" + divisao + "\" no DED+.");
            }
            clicarSeguro(op);
        }
    }

    private List<Avaliacao> lerAvaliacoes() {
        List<Avaliacao> r = new ArrayList<>();
        for (Map<String, Object> m : infoCartoes("avaliacao")) {
            String texto = (String) m.get("texto");
            String nome = m.get("link") != null ? (String) m.get("link") : texto.split("\n")[0];
            Double valor = null;
            var mt = java.util.regex.Pattern.compile("Valor Total:?\\s*([\\d.,]+)").matcher(texto);
            if (mt.find()) valor = Notas.numero(mt.group(1));
            r.add(new Avaliacao(((Number) m.get("i")).intValue(), nome.trim(), valor));
        }
        return r;
    }

    /** Página da lista de avaliações, guardada quando a avaliação abre numa aba nova. */
    private Page paginaAvaliacoes;

    private void abrirAvaliacao(int indice) {
        marcarCartoes("avaliacao", 8_000);
        @SuppressWarnings("unchecked")
        List<Object> alvos = (List<Object>) page.evaluate(JS_ALVOS_AVALIACAO, indice);
        int abasAntes = contexto.pages().size();
        for (int k = 0; k < alvos.size(); k++) {
            Locator l = page.locator("[data-lnd-alvo-av='" + k + "']");
            if (l.count() == 0) continue;
            try {
                l.first().click(new Locator.ClickOptions().setTimeout(3_000));
            } catch (PlaywrightException e) {
                try { l.first().evaluate("el => el.click()"); } catch (PlaywrightException ignored) {}
            }
            if (esperarTelaDeNotas(6_000, abasAntes)) return;
        }
        throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                "Não consegui abrir a tela de lançar notas da avaliação. Captura em " + pastaCapturas());
    }

    /** Espera a tela de lançar notas — na mesma aba ou numa aba nova (troca para ela). */
    private boolean esperarTelaDeNotas(int ms, int abasAntes) {
        long limite = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < limite) {
            if (contexto.pages().size() > abasAntes) {
                Page nova = contexto.pages().get(contexto.pages().size() - 1);
                if (paginaAvaliacoes == null) paginaAvaliacoes = page;
                page = nova;
                try { page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10_000)); }
                catch (PlaywrightException ignored) {}
            }
            if (marcarCartoes("aluno", 0) > 0) return true;
            page.waitForTimeout(250);
        }
        return false;
    }

    private void voltarParaAvaliacoes(String divisao) {
        if (paginaAvaliacoes != null) {
            try { page.close(); } catch (PlaywrightException ignored) {}
            page = paginaAvaliacoes;
            paginaAvaliacoes = null;
            page.bringToFront();
        } else {
            try { page.goBack(new Page.GoBackOptions().setTimeout(10_000)); } catch (PlaywrightException ignored) {}
        }
        esperarCarregar();
        if (!naPaginaAvaliacoes(8_000)) {
            clicar("avaliacoes.voltar");
            if (!naPaginaAvaliacoes(TIMEOUT_MS)) {
                throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO, "Não consegui voltar para Avaliações.");
            }
        }
        // após voltar, o DED+ pode ter mudado a divisão: lê a que está na tela (só troca se precisar)
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) page.evaluate(JS_DIVISAO, RE_DIVISAO);
            divisaoAtual = (String) r.get("atual");
        } catch (PlaywrightException e) {
            divisaoAtual = null;
        }
        garantirAvaliacoes(divisao);
    }

    // ================================================================== leitura de cartões (por texto, não por CSS)

    /**
     * Marca com data-lnd-{tipo}="i" os cartões do tipo (turma / avaliacao / aluno) e devolve quantos achou.
     * Espera até `esperaMs` pelo primeiro cartão.
     */
    private int marcarCartoes(String tipo, int esperaMs) {
        Map<String, Object> arg = Map.of(
                "attr", "data-lnd-" + tipo,
                "ancora", sel.texto("rotulo." + tipo + ".ancora"),
                "exige", Arrays.asList(sel.texto("rotulo." + tipo + ".exige").split("\\s*;;\\s*")),
                "maxAltura", 450);
        long limite = System.currentTimeMillis() + esperaMs;
        int n;
        do {
            n = ((Number) page.evaluate(JS_MARCAR, arg)).intValue();
            if (n > 0 || esperaMs <= 0) return n;
            page.waitForTimeout(250);
        } while (System.currentTimeMillis() < limite);
        return n;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> infoCartoes(String tipo) {
        marcarCartoes(tipo, 8_000);
        return (List<Map<String, Object>>) page.evaluate(JS_INFO, "data-lnd-" + tipo);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> lerCartoesAlunos() {
        marcarCartoes("aluno", TIMEOUT_MS);
        return (List<Map<String, Object>>) page.evaluate(JS_ALUNOS,
                Map.of("rotuloNota", sel.texto("rotulo.aluno.ancora"), "rotuloNR", sel.texto("rotulo.naoRealizada")));
    }

    /** "6º ano\n6º EF AF REG 1 (2026) - 2...\nAvenida ...\nEnsino Religioso\nMANHÃ" → "6º EF AF REG 1 (2026) · Ensino Religioso · MANHÃ" */
    static String rotuloTurma(String texto) {
        List<String> partes = new ArrayList<>();
        for (String l : texto.split("\n")) {
            String s = l.trim();
            if (s.isEmpty()) continue;
            if (s.matches("(?i)^(rua|r\\.|avenida|av\\.|pra[cç]a|travessa|rodovia|estrada|alameda)\\b.*")) continue;
            if (s.matches("(?i)^\\d+º\\s*(ano|s[eé]rie|per[ií]odo)$")) continue;
            partes.add(s.replaceAll("\\s*-\\s*\\d+\\.{0,3}$", ""));
        }
        return String.join(" · ", partes);
    }

    // ================================================================== utilitários de seletor

    private Locator primeiro(String chave) {
        for (String s : sel.get(chave)) {
            Locator l = page.locator(s);
            if (l.count() > 0) return l;
        }
        throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                "Elemento '" + chave + "' não encontrado na tela atual. Ajuste em " + Seletores.ARQUIVO);
    }

    private boolean existeSeletor(String chave) {
        for (String s : sel.get(chave)) {
            try { if (page.locator(s).count() > 0) return true; } catch (PlaywrightException ignored) {}
        }
        return false;
    }

    private boolean existe(String chave, int esperaMs) {
        long limite = System.currentTimeMillis() + esperaMs;
        do {
            for (String s : sel.get(chave)) {
                try {
                    Locator l = page.locator(s);
                    if (l.count() > 0 && l.first().isVisible()) return true;
                } catch (PlaywrightException ignored) {}
            }
            page.waitForTimeout(150);
        } while (System.currentTimeMillis() < limite);
        return false;
    }

    /** Espera a página carregar, no máximo 10 s (sem travar se o DED+ mantiver conexões abertas). */
    private void esperarCarregar() {
        try {
            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (PlaywrightException ignored) {}
        page.waitForTimeout(250);
    }

    /** Clica com limite de 8 s; se o clique "normal" não acontecer, clica via JavaScript. */
    private void clicarSeguro(Locator l) {
        if (l.count() == 0) {
            throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                    "O item que eu ia clicar não está na tela do DED+. Tente de novo.");
        }
        try {
            l.first().click(new Locator.ClickOptions().setTimeout(3_000));
        } catch (PlaywrightException e) {
            log.accept("(clique alternativo)");
            l.first().evaluate("el => { el.scrollIntoView({block: 'center'}); el.click(); }");
        }
    }

    /**
     * Na "Visão Geral da Turma" há atalhos redondos (Chamada do Dia, Avaliações, Aulas, Avaliações Simave).
     * Quem responde ao clique pode ser o ícone, o bloco inteiro ou o texto: tenta cada um até abrir.
     */
    @SuppressWarnings("unchecked")
    private void entrarEmAvaliacoes() {
        List<Object> alvos = (List<Object>) page.evaluate(JS_ATALHO_AVALIACOES);
        for (int k = 0; k < alvos.size(); k++) {
            Locator l = page.locator("[data-lnd-atalho='" + k + "']");
            if (l.count() == 0) continue;
            try {
                l.first().click(new Locator.ClickOptions().setTimeout(5_000));
            } catch (PlaywrightException e) {
                try { l.first().evaluate("el => el.click()"); } catch (PlaywrightException ignored) {}
            }
            if (naPaginaAvaliacoes(6_000)) return;
        }
        // último recurso: os seletores do arquivo de configuração
        try { clicar("turma.abrirAvaliacoes"); } catch (DedException ignored) {}
        if (!naPaginaAvaliacoes(8_000)) {
            throw new DedException(DedException.Tipo.ELEMENTO_NAO_ENCONTRADO,
                    "Não encontrei a tela de Avaliações desta turma. Captura em " + pastaCapturas());
        }
    }

    /** Lê nome + matrícula dos alunos no "Relatório Geral" da Visão Geral da Turma. Vazio se não conseguir. */
    @SuppressWarnings("unchecked")
    private List<Aluno> lerAlunosVisaoGeral() {
        try {
            Map<String, Object> r = (Map<String, Object>) page.evaluate(JS_ALUNOS_VISAO_GERAL);
            List<Map<String, Object>> lista = (List<Map<String, Object>>) r.get("alunos");
            Number total = (Number) r.get("total");
            List<Aluno> alunos = new ArrayList<>();
            for (Map<String, Object> m : lista) alunos.add(new Aluno((String) m.get("matricula"), (String) m.get("nome")));
            // só usa se bater EXATAMENTE com o "Total de alunos" da tela (lista incompleta ou com sobra -> plano B)
            log.accept("Visão Geral: " + alunos.size() + " aluno(s) lidos" + (total != null ? " de " + total : "") + ".");
            if (alunos.isEmpty() || (total != null && total.intValue() > 0 && alunos.size() != total.intValue())) return List.of();
            return alunos;
        } catch (PlaywrightException e) {
            return List.of();
        }
    }

    private void clicar(String chave) {
        clicarSeguro(primeiro(chave).first());
        esperarCarregar();
    }

    private void preencher(String chave, String valor) {
        Locator l = primeiro(chave).first();
        l.focus();
        l.fill(valor);
        l.press("Tab"); // o Seg.ID (ZK) só registra o valor ao sair do campo
    }

    private void pausa() { page.waitForTimeout(Config.pausaEntreAcoesMs()); }

    public static Path pastaCapturas() { return Config.PASTA.resolve("capturas"); }

    private void capturarTela(String motivo) {
        try {
            Files.createDirectories(pastaCapturas());
            String nome = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + motivo + ".png";
            page.screenshot(new Page.ScreenshotOptions().setPath(pastaCapturas().resolve(nome)).setFullPage(true).setTimeout(5_000));
        } catch (Exception ignored) {}
        try {
            // código da página (sem senhas: campos de senha são esvaziados) — para diagnosticar mudanças no DED+
            String html = (String) page.evaluate("() => { document.querySelectorAll('input[type=password]').forEach(i => i.value=''); "
                    + "return document.documentElement.outerHTML; }");
            String nome = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + motivo + ".html";
            Files.writeString(pastaCapturas().resolve(nome), "<!-- " + page.url() + " -->\n" + html);
        } catch (Exception ignored) {}
    }

    /** Modo calibração: mostra a janela e abre o inspetor do Playwright. */
    public CompletableFuture<Void> calibrar() {
        return naThread((Login<Void>) () -> {
            if (page == null) {
                throw new DedException(DedException.Tipo.OUTRO, "Entre no DED+ primeiro; a calibração usa o navegador já aberto.");
            }
            mostrar();
            if (page.url().isBlank() || page.url().startsWith("about:")) page.navigate(Config.urlDed());
            log.accept("Calibração: use 'Pick locator' no inspetor e ajuste " + Seletores.ARQUIVO + ". Feche o inspetor para continuar.");
            page.pause();
            restaurarJanela();
            return null;
        });
    }

    // ================================================================== JavaScript executado na página

    /** Acha cartões: parte de um texto-âncora e sobe até o menor bloco que contém os textos exigidos. */
    private static final String JS_MARCAR = """
        ({attr, ancora, exige, maxAltura}) => {
          document.querySelectorAll('[' + attr + ']').forEach(e => e.removeAttribute(attr));
          const re = new RegExp(ancora, 'i');
          const exs = exige.map(x => new RegExp(x, 'i'));
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          const cards = [];
          let n;
          while ((n = w.nextNode())) {
            if (!re.test(n.textContent)) continue;
            let e = n.parentElement;
            if (!e || e.offsetParent === null) continue;
            while (e && e !== document.body) {
              const t = e.textContent || '';   // textContent não força o navegador a redesenhar (innerText força)
              if (exs.every(x => x.test(t))) break;
              e = e.parentElement;
            }
            if (!e || e === document.body) continue;
            if (maxAltura && e.getBoundingClientRect().height > maxAltura) continue;
            if (cards.some(c => c === e || c.contains(e) || e.contains(c))) continue;
            cards.push(e);
          }
          cards.forEach((c, i) => c.setAttribute(attr, String(i)));
          return cards.length;
        }
        """;

    private static final String JS_INFO = """
        attr => [...document.querySelectorAll('[' + attr + ']')].map(c => ({
          i: Number(c.getAttribute(attr)),
          texto: c.innerText.trim(),
          link: c.querySelector('a') ? c.querySelector('a').innerText.trim() : null
        }))
        """;

    /** Lê matrícula, nome, valor atual da nota e "não realizada" de cada cartão de aluno; marca o campo da nota. */
    private static final String JS_ALUNOS = """
        ({rotuloNota, rotuloNR}) => {
          const reNota = new RegExp(rotuloNota, 'i');
          const reNR = new RegExp(rotuloNR, 'i');
          document.querySelectorAll('[data-lnd-nota]').forEach(e => e.removeAttribute('data-lnd-nota'));
          return [...document.querySelectorAll('[data-lnd-aluno]')].map(c => {
            const i = Number(c.getAttribute('data-lnd-aluno'));
            const txt = c.innerText;
            const mat = (txt.match(/\\b\\d{6,10}\\b/) || [''])[0];
            const link = c.querySelector('a');
            const linhas = txt.split('\\n').map(s => s.trim()).filter(Boolean);
            const nome = (link ? link.innerText : (linhas.find(l => /^[A-ZÀ-Ý][A-ZÀ-Ý'. -]{5,}$/.test(l)) || '')).trim();
            // campo da nota = primeiro input de texto depois do rótulo "Nota:"
            const campos = [...c.querySelectorAll('input')].filter(x => !['hidden','radio','checkbox'].includes(x.type));
            let rotulo = null;
            const w = document.createTreeWalker(c, NodeFilter.SHOW_TEXT);
            let n; while ((n = w.nextNode())) { if (reNota.test(n.textContent)) { rotulo = n; break; } }
            let campo = campos[0];
            if (rotulo) {
              const depois = campos.find(x => rotulo.compareDocumentPosition(x) & Node.DOCUMENT_POSITION_FOLLOWING);
              if (depois) campo = depois;
            }
            if (campo) campo.setAttribute('data-lnd-nota', String(i));
            // "Não realizada" marcada?
            const radios = [...c.querySelectorAll('input[type=radio], input[type=checkbox], [role=radio], [role=checkbox]')];
            const nr = radios.some(r => (r.checked || r.getAttribute('aria-checked') === 'true'));
            return { i, matricula: mat, nome, valor: campo ? campo.value : '', nr };
          });
        }
        """;

    private static final String JS_MARCAR_NR = """
        ({i, rotulo}) => {
          const c = document.querySelector('[data-lnd-aluno="' + i + '"]');
          if (!c) return false;
          const re = new RegExp(rotulo, 'i');
          const alvos = [...c.querySelectorAll('input[type=radio], input[type=checkbox], [role=radio], [role=checkbox], mat-radio-button, mat-checkbox')];
          let alvo = alvos.find(a => re.test((a.closest('label') || a.parentElement || a).innerText || ''));
          if (!alvo) alvo = alvos[alvos.length - 1];
          if (!alvo) return false;
          if (alvo.checked || alvo.getAttribute('aria-checked') === 'true') return true;
          alvo.setAttribute('data-lnd-nr', String(i));
          return true;
        }
        """;

    /**
     * Acha o seletor de divisão perto do texto "Seleciona a divisão" (ou o primeiro texto do tipo
     * "3º Trimestre" que seja clicável). Marca-o com data-lnd-divisao="gatilho".
     */
    private static final String JS_DIVISAO = """
        (re) => {
          const rx = new RegExp(re, 'i');
          document.querySelectorAll('[data-lnd-divisao]').forEach(e => e.removeAttribute('data-lnd-divisao'));
          document.querySelectorAll('[data-lnd-antes]').forEach(e => e.removeAttribute('data-lnd-antes'));
          // 1) lista comum
          for (const s of document.querySelectorAll('select')) {
            const ops = [...s.options].map(o => o.text.trim()).filter(t => rx.test(t));
            if (ops.length) {
              s.setAttribute('data-lnd-divisao', 'gatilho');
              const atual = s.options[s.selectedIndex] ? s.options[s.selectedIndex].text.trim() : null;
              return { tipo: 'select', atual, opcoes: [...s.options].map(o => o.text.trim()).filter(Boolean) };
            }
          }
          // 2) menu próprio: elemento visível cujo texto é só "3º Trimestre", de preferência perto do rótulo
          const visivel = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
          // percorre só os TEXTOS da página (rápido) e pega o elemento que os contém
          const folhas = []; let rot = null;
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            const t = n.textContent;
            if (!rot && /seleciona a divis/i.test(t)) rot = n.parentElement;
            if (!/\\d/.test(t)) continue;
            let e = n.parentElement;
            if (e && rx.test(e.textContent || '') && visivel(e) && !folhas.includes(e)) folhas.push(e);
          }
          folhas.forEach(e => e.setAttribute('data-lnd-antes', '1'));
          const clicavel = e => e.closest('button, select, [role=combobox], [role=button], mat-select, .mat-mdc-select, [aria-haspopup], [class*=select], [class*=dropdown]') || e;
          let alvo = null;
          if (rot) {
            const caixa = rot.closest('div, section, form') || rot.parentElement;
            alvo = folhas.find(e => (caixa && caixa.contains(e)) || (rot.parentElement && rot.parentElement.contains(e)));
          }
          if (!alvo) alvo = folhas.find(e => clicavel(e) !== e);
          if (!alvo) return { tipo: null, atual: folhas.length ? folhas[0].textContent.trim() : null, opcoes: [] };
          clicavel(alvo).setAttribute('data-lnd-divisao', 'gatilho');
          return { tipo: 'custom', atual: alvo.textContent.trim(), opcoes: [] };
        }
        """;

    /** Depois de abrir o menu: opções novas (que não existiam antes do clique) com texto de divisão. */
    private static final String JS_DIVISAO_OPCOES = """
        (re) => {
          const rx = new RegExp(re, 'i');
          document.querySelectorAll('[data-lnd-divisao-opcao]').forEach(e => e.removeAttribute('data-lnd-divisao-opcao'));
          const visivel = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
          const ops = [];
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            const e = n.parentElement;
            if (e && rx.test(e.textContent || '') && !e.hasAttribute('data-lnd-antes') && visivel(e) && !ops.includes(e)) ops.push(e);
          }
          const vistos = new Set(); const nomes = [];
          for (const e of ops) {
            const t = e.textContent.trim();
            if (vistos.has(t)) continue;
            vistos.add(t); nomes.push(t);
            e.setAttribute('data-lnd-divisao-opcao', t);
          }
          return nomes;
        }
        """;

    /**
     * Marca, em ordem de preferência, os possíveis pontos de clique do atalho "Avaliações"
     * (texto exatamente "Avaliações" — não confunde com "Avaliações Simave").
     */
    private static final String JS_ATALHO_AVALIACOES = """
        () => {
          document.querySelectorAll('[data-lnd-atalho]').forEach(e => e.removeAttribute('data-lnd-atalho'));
          const exato = e => /^\\s*avalia[cç][oõ]es\\s*$/i.test(e.textContent || '');
          const visivel = e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
          let folha = null;
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            const e = n.parentElement;
            if (e && exato(e) && visivel(e)) { folha = e; break; }
          }
          if (!folha) return [];
          const alvos = [];
          const add = e => { if (e && e !== document.body && !alvos.includes(e)) alvos.push(e); };
          add(folha.closest('a, button, [role=button], [role=link], [routerlink], [ng-reflect-router-link]'));
          // ancestral próximo com cursor de "mãozinha" e que contenha só este atalho
          let p = folha;
          for (let i = 0; i < 4 && p.parentElement; i++) {
            p = p.parentElement;
            if (/avalia[cç][oõ]es\\s+simave/i.test(p.textContent || '') || /chamada/i.test(p.textContent || '')) break;
            if (getComputedStyle(p).cursor === 'pointer') { add(p); break; }
          }
          // ícone do atalho (imagem/svg/círculo) dentro do mesmo bloco
          const bloco = folha.parentElement;
          if (bloco) add([...bloco.querySelectorAll('img, svg, mat-icon, i, button, a, div')].find(e => e !== folha && !e.contains(folha) && visivel(e)));
          add(bloco);
          add(folha);
          alvos.forEach((e, i) => e.setAttribute('data-lnd-atalho', String(i)));
          return alvos.map(e => e.tagName);
        }
        """;

    /** Relatório Geral da Visão Geral da Turma: cada aluno = link com o nome + matrícula logo abaixo. */
    private static final String JS_ALUNOS_VISAO_GERAL = """
        () => {
          const tot = (document.body.innerText.match(/Total de alunos:?\\s*(\\d+)/i) || [])[1];
          const alunos = []; const vistos = new Set();
          const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            const mat = n.textContent.trim();
            if (!/^\\d{6,10}$/.test(mat) || vistos.has(mat)) continue;
            // sobe até o bloco do aluno: o menor bloco com UM link de nome (em maiúsculas).
            // Se o primeiro bloco com link já tiver vários nomes, é a lista inteira -> não é matrícula de aluno.
            const ehNome = a => { const t = (a.textContent || '').trim(); return /^[A-ZÀ-Ý][A-ZÀ-Ý'. -]{4,}$/.test(t) && t.includes(' '); };
            let e = n.parentElement, link = null;
            for (let i = 0; i < 4 && e && e !== document.body; i++, e = e.parentElement) {
              const nomes = [...e.querySelectorAll('a')].filter(ehNome);
              if (nomes.length === 1) { link = nomes[0]; break; }
              if (nomes.length > 1) break;
            }
            if (!link) continue;
            vistos.add(mat);
            alunos.push({ matricula: mat, nome: link.textContent.trim().replace(/\\s+/g, ' ') });
          }
          return { total: tot ? Number(tot) : null, alunos };
        }
        """;

    /**
     * Pontos de clique para abrir a avaliação nº i (em ordem): o título sublinhado, o link/botão
     * que o contém, e o próprio cartão. Evita o lápis (editar), "Copiar" e "Excluir".
     */
    private static final String JS_ALVOS_AVALIACAO = """
        (i) => {
          document.querySelectorAll('[data-lnd-alvo-av]').forEach(e => e.removeAttribute('data-lnd-alvo-av'));
          const card = document.querySelector('[data-lnd-avaliacao="' + i + '"]');
          if (!card) return [];
          const proibido = e => /copiar|excluir|editar|valor total|tipo:|divis/i.test(e.textContent || '');
          // título: primeiro texto do cartão em maiúsculas
          let titulo = null;
          const w = document.createTreeWalker(card, NodeFilter.SHOW_TEXT);
          let n;
          while ((n = w.nextNode())) {
            const t = n.textContent.trim();
            if (t.length >= 3 && t === t.toUpperCase() && /[A-ZÀ-Ý]/.test(t) && !/^NOTA REGISTRADA$/i.test(t)) { titulo = n.parentElement; break; }
          }
          const alvos = [];
          const add = e => { if (e && card.contains(e) && !alvos.includes(e)) alvos.push(e); };
          if (titulo) {
            add(titulo);
            add(titulo.closest('a, button, [role=button], [role=link], [routerlink]'));
            let p = titulo;
            for (let k = 0; k < 3 && p.parentElement && p.parentElement !== card; k++) {
              p = p.parentElement;
              if (getComputedStyle(p).cursor === 'pointer' && !proibido(p)) { add(p); break; }
            }
          }
          [...card.querySelectorAll('a')].filter(a => !proibido(a) && (a.textContent || '').trim().length > 2).forEach(add);
          add(card);
          alvos.forEach((e, k) => e.setAttribute('data-lnd-alvo-av', String(k)));
          return alvos.map(e => e.tagName);
        }
        """;

    /** Botão "início" (casinha) no topo do DED+. Marca com data-lnd-inicio; true se achou. */
    private static final String JS_BOTAO_INICIO = """
        () => {
          document.querySelectorAll('[data-lnd-inicio]').forEach(e => e.removeAttribute('data-lnd-inicio'));
          const cands = [...document.querySelectorAll('a, button, [role=button], mat-icon, i, svg, img')];
          const ehInicio = e => {
            const t = ((e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '') + ' ' +
                       (e.getAttribute('mattooltip') || '') + ' ' + (e.tagName === 'MAT-ICON' || e.tagName === 'I' ? e.textContent : '') + ' ' +
                       (e.getAttribute('class') || '') + ' ' + (e.getAttribute('src') || '') + ' ' + (e.getAttribute('href') || '')).toLowerCase();
            return /\\bhome\\b|in[ií]cio|house/.test(t);
          };
          const r = e => e.getBoundingClientRect();
          const alvo = cands.find(e => ehInicio(e) && r(e).width > 0 && r(e).top < 120);
          if (!alvo) return false;
          (alvo.closest('a, button, [role=button]') || alvo).setAttribute('data-lnd-inicio', '1');
          return true;
        }
        """;

    /** Linhas da tabela "Seleção de Unidades": SIGLA | CODIGO | NOME. */
    private static final String JS_ESCOLAS = """
        () => {
          const out = [];
          let i = 0;
          for (const tr of document.querySelectorAll('tr')) {
            const tds = [...tr.querySelectorAll('td')].map(td => td.innerText.trim()).filter(Boolean);
            const cod = tds.find(t => /^\\d{5,8}$/.test(t));
            if (!cod) continue;
            const nome = tds[tds.length - 1];
            tr.setAttribute('data-lnd-escola', String(i));
            out.push({ i, codigo: cod, nome });
            i++;
          }
          return out;
        }
        """;
}
