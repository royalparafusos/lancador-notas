package br.com.lancadornotas.model;

import javafx.beans.property.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Uma linha da tabela de conferência: um aluno da folha, com uma nota por coluna lida.
 */
public class LinhaNota {

    public enum Status { CONFERIR, PRONTO, LANCADO, ERRO, IGNORAR }

    private final Integer numero;
    private final StringProperty nomeLido = new SimpleStringProperty();
    private final ObjectProperty<Aluno> aluno = new SimpleObjectProperty<>();
    private final List<StringProperty> notas = new ArrayList<>();
    private final DoubleProperty confianca = new SimpleDoubleProperty(1);
    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.CONFERIR);
    private final StringProperty mensagem = new SimpleStringProperty("");
    /** true quando o professor mexeu/confirmou a linha manualmente. */
    private boolean conferidaPeloProfessor;
    /** true quando o professor mandou não lançar a linha. */
    private boolean ignoradaPeloProfessor;
    /** Resultado do casamento de nome: true = seguro para lançar sem conferência. */
    private boolean casamentoSeguro;
    private String motivoConferir = "";

    public LinhaNota(Integer numero, String nomeLido, List<String> notasLidas) {
        this.numero = numero;
        this.nomeLido.set(nomeLido);
        for (String n : notasLidas) notas.add(new SimpleStringProperty(n == null ? "" : n.trim()));
    }

    public Integer getNumero() { return numero; }
    public StringProperty nomeLidoProperty() { return nomeLido; }
    public String getNomeLido() { return nomeLido.get(); }
    public ObjectProperty<Aluno> alunoProperty() { return aluno; }
    public Aluno getAluno() { return aluno.get(); }
    public void setAluno(Aluno a) { aluno.set(a); }

    public StringProperty notaProperty(int coluna) {
        while (notas.size() <= coluna) notas.add(new SimpleStringProperty(""));
        return notas.get(coluna);
    }
    public String getNota(int coluna) { return notaProperty(coluna).get(); }

    public DoubleProperty confiancaProperty() { return confianca; }
    public double getConfianca() { return confianca.get(); }
    public void setConfianca(double v) { confianca.set(v); }
    public ObjectProperty<Status> statusProperty() { return status; }
    public Status getStatus() { return status.get(); }
    public void setStatus(Status s) { status.set(s); }
    public StringProperty mensagemProperty() { return mensagem; }
    public void setMensagem(String m) { mensagem.set(m == null ? "" : m); }
    public boolean isConferidaPeloProfessor() { return conferidaPeloProfessor; }
    public void setConferidaPeloProfessor(boolean v) { conferidaPeloProfessor = v; if (v) ignoradaPeloProfessor = false; }
    public boolean isIgnoradaPeloProfessor() { return ignoradaPeloProfessor; }
    public void setIgnoradaPeloProfessor(boolean v) { ignoradaPeloProfessor = v; }
    public boolean isCasamentoSeguro() { return casamentoSeguro; }
    public String getMotivoConferir() { return motivoConferir; }
    public void setCasamento(boolean seguro, String motivo) { casamentoSeguro = seguro; motivoConferir = motivo == null ? "" : motivo; }
}
