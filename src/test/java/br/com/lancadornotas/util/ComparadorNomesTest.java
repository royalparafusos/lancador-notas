package br.com.lancadornotas.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ComparadorNomesTest {

    private final List<String> turma = List.of(
            "ANA CLARA DE SOUZA",
            "ANA LUIZA PEREIRA",
            "JOÃO PEDRO DA SILVA",
            "JOÃO VITOR ALMEIDA",
            "MARIA EDUARDA GONÇALVES",
            "PEDRO HENRIQUE OLIVEIRA");

    @Test
    void ignoraAcentosEMaiusculas() {
        assertEquals("JOÃO PEDRO DA SILVA", ComparadorNomes.melhor("joao pedro silva", turma).nomeOficial());
    }

    @Test
    void aceitaAbreviacao() {
        var r = ComparadorNomes.melhor("João P. Silva", turma);
        assertEquals("JOÃO PEDRO DA SILVA", r.nomeOficial());
        assertTrue(r.pontuacao() >= ComparadorNomes.LIMIAR_AUTOMATICO, "pontuação " + r.pontuacao());
    }

    @Test
    void aceitaErroDeGrafia() {
        assertEquals("MARIA EDUARDA GONÇALVES", ComparadorNomes.melhor("Maria Edurda Goncalves", turma).nomeOficial());
    }

    @Test
    void distingueHomonimosPeloSobrenome() {
        assertEquals("ANA LUIZA PEREIRA", ComparadorNomes.melhor("Ana Luiza", turma).nomeOficial());
        assertEquals("JOÃO VITOR ALMEIDA", ComparadorNomes.melhor("Joao Vitor", turma).nomeOficial());
    }

    @Test
    void soPrimeiroNomeAmbiguoVaiParaConferencia() {
        var r = ComparadorNomes.melhor("Ana", turma);
        assertTrue(r == null || r.pontuacao() < ComparadorNomes.LIMIAR_AUTOMATICO,
                "'Ana' sozinho não deve ser lançado automaticamente");
    }

    @Test
    void nomeInexistenteNaoCasa() {
        var r = ComparadorNomes.melhor("Wellington Cardoso", turma);
        assertTrue(r == null || r.pontuacao() < ComparadorNomes.LIMIAR_AUTOMATICO);
    }

    @Test
    void primeiroNomeUnicoNaTurmaPassaDireto() {
        var r = ComparadorNomes.melhor("Maria", turma);
        assertEquals("MARIA EDUARDA GONÇALVES", r.nomeOficial());
        assertTrue(r.pontuacao() >= ComparadorNomes.LIMIAR_AUTOMATICO);
    }

    @Test
    void inicialDoSobrenomeDesempata() {
        List<String> t = List.of("LUCAS CARDOSO VIEIRA", "LUCAS DUARTE MOREIRA", "YASMIN SANTOS OLIVEIRA", "YASMIN SOUZA FREITAS");
        assertEquals("LUCAS CARDOSO VIEIRA", ComparadorNomes.melhor("Lucas C.", t).nomeOficial());
        assertEquals("LUCAS DUARTE MOREIRA", ComparadorNomes.melhor("Lucas D.", t).nomeOficial());
        assertEquals("YASMIN SOUZA FREITAS", ComparadorNomes.melhor("Yasmin Souza", t).nomeOficial());
        var soLucas = ComparadorNomes.melhor("Lucas", t);
        assertTrue(soLucas.pontuacao() < ComparadorNomes.LIMIAR_AUTOMATICO, "'Lucas' sozinho é ambíguo");
    }
}
