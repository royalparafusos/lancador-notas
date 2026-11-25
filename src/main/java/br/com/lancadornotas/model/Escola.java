package br.com.lancadornotas.model;

/** Unidade escolar da tela "Seleção de Unidades" do Seg.ID. */
public record Escola(int indice, String codigo, String nome) {
    @Override public String toString() { return codigo + " — " + nome; }
}
