package br.com.lancadornotas;

/**
 * Ponto de entrada do .jar. Precisa ser uma classe que NÃO estende Application,
 * senão o JavaFX reclama ao rodar a partir de um jar "gordo".
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
