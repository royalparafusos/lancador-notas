package br.com.lancadornotas.ded;

import br.com.lancadornotas.model.Aluno;
import br.com.lancadornotas.model.Avaliacao;
import br.com.lancadornotas.model.Escola;
import br.com.lancadornotas.model.Turma;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * O que o programa precisa do DED+. Duas formas de fazer:
 *  - {@link DedExtensao} (padrão): o professor usa o DED+ no PRÓPRIO navegador, e uma extensão
 *    (o "conector") faz o trabalho dentro da página já aberta e logada;
 *  - {@link DedClient} (antigo): o programa abre uma janela de navegador própria.
 */
public interface Ded extends AutoCloseable {

    record ResultadoLogin(List<Escola> escolas, String escolaAtual) {
        public boolean precisaEscolherEscola() { return !escolas.isEmpty(); }
    }

    /** Resultado do lançamento de UM aluno numa avaliação. */
    record ResultadoAluno(boolean ok, String mensagem) {}

    /** Se já houver DED+ aberto e logado, conecta sem login. null se não houver. */
    CompletableFuture<ResultadoLogin> usarDedJaAberto();

    /** Espera o professor entrar no DED+ (e conecta). */
    CompletableFuture<ResultadoLogin> entrar();

    void continuarAgora();

    CompletableFuture<String> selecionarEscola(Escola e);

    CompletableFuture<List<Escola>> trocarEscola();

    CompletableFuture<List<Turma>> listarTurmas();

    /** Abre a turma e devolve as divisões (a selecionada primeiro). */
    CompletableFuture<List<String>> abrirTurma(Turma t);

    CompletableFuture<List<Avaliacao>> listarAvaliacoes(String divisao);

    CompletableFuture<List<Aluno>> listarAlunos(String divisao);

    /** @param notas matrícula → nota ("8,5") ou "NR" */
    CompletableFuture<Map<String, ResultadoAluno>> lancarNotas(String divisao, Avaliacao av,
                                                             Map<String, String> notas, Consumer<String> progresso);

    CompletableFuture<Void> calibrar();

    /** true no modo conector (extensão). */
    default boolean usaExtensao() { return false; }

    /** No modo conector: a extensão está conversando com o programa agora? */
    default boolean conectorConectado() { return false; }

    @Override
    void close();
}
