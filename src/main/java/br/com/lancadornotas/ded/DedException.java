package br.com.lancadornotas.ded;

public class DedException extends RuntimeException {

    public enum Tipo { LOGIN_INVALIDO, CAPTCHA, ELEMENTO_NAO_ENCONTRADO, NAO_SALVOU, OUTRO }

    private final Tipo tipo;

    public DedException(Tipo tipo, String msg) {
        super(msg);
        this.tipo = tipo;
    }

    public DedException(Tipo tipo, String msg, Throwable causa) {
        super(msg, causa);
        this.tipo = tipo;
    }

    public Tipo getTipo() { return tipo; }
}
