package br.com.lancadornotas.model;

/** Aluno como aparece na tela de lançamento do DED+: matrícula + nome. */
public record Aluno(String matricula, String nome) {
    /** Texto exibido na tabela: nome + matrícula (garante que dois homônimos não se confundam). */
    public String rotulo() { return nome + " · " + matricula; }
    @Override public String toString() { return rotulo(); }
}
