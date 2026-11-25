package br.com.lancadornotas.util;

import java.text.Normalizer;
import java.util.*;

/**
 * Casa o nome lido na foto ("Joao P. Silva") com o nome oficial do DED ("JOÃO PEDRO DA SILVA").
 * Ignora acentos, maiúsculas, preposições (da, de, dos...) e aceita abreviações e pequenos erros de grafia.
 */
public final class ComparadorNomes {

    /** Acima disso o casamento é automático; abaixo vai para conferência manual. */
    public static final double LIMIAR_AUTOMATICO = 0.85;
    /** Abaixo disso nem sugere. */
    public static final double LIMIAR_SUGESTAO = 0.55;

    private static final Set<String> CONECTIVOS = Set.of("da", "de", "do", "das", "dos", "e");

    private ComparadorNomes() {}

    public record Resultado(String nomeOficial, double pontuacao) {}

    /** Melhor candidato da lista oficial para o nome lido (ou null se nenhum passar do limiar de sugestão). */
    public static Resultado melhor(String nomeLido, Collection<String> oficiais) {
        Resultado melhor = null;
        double segundo = 0;
        for (String oficial : oficiais) {
            double p = similaridade(nomeLido, oficial);
            if (melhor == null || p > melhor.pontuacao()) {
                if (melhor != null) segundo = melhor.pontuacao();
                melhor = new Resultado(oficial, p);
            } else if (p > segundo) {
                segundo = p;
            }
        }
        if (melhor == null || melhor.pontuacao() < LIMIAR_SUGESTAO) return null;

        double p = melhor.pontuacao();
        // Segurança: nunca lançar sozinho quando há dúvida real.
        double folga = melhor.pontuacao() - segundo;
        if (folga < 0.06) p = Math.min(p, 0.80);                              // dois alunos parecidos ("Ana Clara" x "Ana Luiza")
        if (tokens(nomeLido).size() < 2 && folga < 0.10) p = Math.min(p, 0.80); // só o 1º nome e ele não é único na turma
        return new Resultado(melhor.nomeOficial(), p);
    }

    public static String normalizar(String s) {
        if (s == null) return "";
        String semAcento = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
    }

    static List<String> tokens(String s) {
        List<String> t = new ArrayList<>();
        for (String p : normalizar(s).split(" ")) {
            if (!p.isEmpty() && !CONECTIVOS.contains(p)) t.add(p);
        }
        return t;
    }

    /** 0 a 1. */
    public static double similaridade(String lido, String oficial) {
        List<String> a = tokens(lido);
        List<String> b = tokens(oficial);
        if (a.isEmpty() || b.isEmpty()) return 0;

        // Cada palavra lida procura a melhor palavra do nome oficial (aceita abreviação "P" -> "Pedro").
        double soma = 0;
        boolean[] usado = new boolean[b.size()];
        for (String ta : a) {
            double melhor = 0;
            int idx = -1;
            for (int j = 0; j < b.size(); j++) {
                if (usado[j]) continue;
                double s = similaridadePalavra(ta, b.get(j));
                if (s > melhor) { melhor = s; idx = j; }
            }
            if (idx >= 0 && melhor > 0.6) usado[idx] = true;
            soma += melhor;
        }
        double cobertura = soma / a.size();

        // Primeiro nome pesa mais: é o que o professor quase sempre escreve certo.
        double primeiro = similaridadePalavra(a.get(0), b.get(0));

        // Penaliza levemente quando o nome lido tem bem menos palavras (ambiguidade "Ana" vs "Ana Clara").
        double proporcao = Math.min(1.0, (double) a.size() / Math.min(b.size(), 3));

        return 0.55 * cobertura + 0.30 * primeiro + 0.15 * proporcao;
    }

    private static double similaridadePalavra(String a, String b) {
        if (a.equals(b)) return 1.0;
        // abreviação: "p" ou "p." casa com "pedro"
        if (a.length() == 1 && b.startsWith(a)) return 0.9;
        if (b.length() == 1 && a.startsWith(b)) return 0.9;
        int dist = levenshtein(a, b);
        return 1.0 - (double) dist / Math.max(a.length(), b.length());
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int custo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + custo);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
