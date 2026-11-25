package br.com.lancadornotas.model;

/** Cartão de turma da tela inicial do DED+ (ex.: "9º EF AF REG 1 (2026) · Ensino Religioso · MANHÃ"). */
public record Turma(int indice, String rotulo) {
    @Override public String toString() { return rotulo; }
}
