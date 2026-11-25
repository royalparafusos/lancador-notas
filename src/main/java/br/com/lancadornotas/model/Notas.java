package br.com.lancadornotas.model;

import java.util.Locale;

/** Utilidades de nota: o DED+ mostra notas como "8,00". */
public final class Notas {
    public static final String NAO_REALIZADA = "NR";
    private static final Locale BR = Locale.forLanguageTag("pt-BR");

    private Notas() {}

    /** "8", "8,0", "8.5", " 10,00 " -> número. "NR", "", "-", "x" -> null. */
    public static Double numero(String s) {
        if (s == null) return null;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    public static boolean naoRealizada(String s) {
        return s != null && s.trim().equalsIgnoreCase(NAO_REALIZADA);
    }

    /** Vazio, traço, X, visto (✓) e afins não são notas. */
    public static boolean vazia(String s) {
        return s == null || s.isBlank() || s.trim().matches("(?i)[-–—x✓✔✗✘/.\\s]+");
    }

    /** 8.0 -> "8,00" (formato do DED+). */
    public static String formatar(double v) {
        return String.format(BR, "%.2f", v);
    }

    public static boolean iguais(String a, String b) {
        Double x = numero(a), y = numero(b);
        if (x == null || y == null) return false;
        return Math.abs(x - y) < 0.001;
    }
}
