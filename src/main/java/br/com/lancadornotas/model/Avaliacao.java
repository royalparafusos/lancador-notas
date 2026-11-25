package br.com.lancadornotas.model;

/** Cartão de avaliação da tela "Avaliações" (ex.: "PRODUÇÃO DO TRABALHO", valor 10). */
public record Avaliacao(int indice, String nome, Double valor) {
    @Override public String toString() {
        return (indice + 1) + ". " + nome + (valor != null ? "  (vale " + Notas.formatar(valor) + ")" : "");
    }
}
