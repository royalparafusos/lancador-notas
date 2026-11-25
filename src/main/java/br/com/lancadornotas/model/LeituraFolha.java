package br.com.lancadornotas.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Resultado da leitura da foto: cabeçalho + colunas de atividades + alunos com uma nota por coluna.
 */
public class LeituraFolha {
    /** Preenchido quando a foto é da folha gerada pelo próprio programa (código "LND|..."). */
    public String codigoModelo = "";
    public String turma = "";
    public String disciplina = "";
    public String divisao = "";
    public List<Coluna> colunas = new ArrayList<>();
    public List<Item> alunos = new ArrayList<>();
    public List<String> observacoes = new ArrayList<>();

    public static class Coluna {
        public String nome = "";          // "Atv 1", "Atv 2", "Avalia 1", "Recup."...
        public Double valor;              // valor escrito no cabeçalho (ex.: 10,0)
    }

    public static class Item {
        public Integer numero;            // Nº da linha
        public String nome = "";
        public List<String> notas = new ArrayList<>();   // uma por coluna, na mesma ordem de "colunas"
        public double confianca = 1.0;
    }

    public boolean ehModeloDoPrograma() {
        return codigoModelo != null && codigoModelo.startsWith("LND");
    }
}
