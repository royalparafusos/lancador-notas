package br.com.lancadornotas.ocr;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.model.LeituraFolha;

import java.nio.file.Path;
import java.util.List;

/** Lê a foto da folha de notas. Há dois motores: Groq (gratuito) e Claude/Anthropic (pago). */
public interface LeitorFolha {

    /**
     * @param foto           arquivo de imagem (jpg/png)
     * @param nomesDaTurma   lista oficial de alunos vinda do DED (ajuda a acertar nomes mal escritos); pode ser vazia
     */
    LeituraFolha ler(Path foto, List<String> nomesDaTurma) throws Exception;

    /** Escolhe o motor: Groq se houver chave do Groq; senão Claude se houver chave da Anthropic; senão Groq (vai pedir a chave). */
    static LeitorFolha criar() {
        if (!Config.chaveGroq().isBlank()) return new LeitorFolhaGroq();
        if (!Config.chaveApiIA().isBlank()) return new LeitorFolhaClaude();
        return new LeitorFolhaGroq();
    }
}
