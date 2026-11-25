package br.com.lancadornotas.ocr;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.model.LeituraFolha;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Lê a folha com a API de visão da Anthropic (Claude). Funciona bem com letra de mão,
 * que é onde OCR tradicional (Tesseract) costuma falhar.
 */
public class LeitorFolhaClaude implements LeitorFolha {

    private static final String URL = "https://api.anthropic.com/v1/messages";
    private static final long LIMITE_BYTES = 4_500_000; // a API aceita até ~5 MB por imagem

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public LeituraFolha ler(Path foto, List<String> nomesDaTurma) throws Exception {
        String chave = Config.chaveApiIA();
        if (chave.isBlank()) {
            throw new IllegalStateException("O programa ainda não tem a chave de leitura de fotos. "
                    + "Peça a quem instalou o programa para configurar (botão Configurações).");
        }

        byte[] bytes = ImagemUtil.prepararParaEnvio(foto, LIMITE_BYTES);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", Config.modeloIA());
        corpo.put("max_tokens", 8192);
        corpo.put("system", instrucoes());

        ArrayNode content = json.createArrayNode();
        ObjectNode img = content.addObject();
        img.put("type", "image");
        ObjectNode src = img.putObject("source");
        src.put("type", "base64");
        src.put("media_type", "image/jpeg");
        src.put("data", base64);

        ObjectNode txt = content.addObject();
        txt.put("type", "text");
        txt.put("text", pedido(nomesDaTurma));

        ArrayNode msgs = corpo.putArray("messages");
        ObjectNode msg = msgs.addObject();
        msg.put("role", "user");
        msg.set("content", content);

        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(120))
                .header("x-api-key", chave)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Erro da API de leitura (" + resp.statusCode() + "): " + resp.body());
        }

        JsonNode raiz = json.readTree(resp.body());
        StringBuilder texto = new StringBuilder();
        for (JsonNode bloco : raiz.path("content")) {
            if ("text".equals(bloco.path("type").asText())) texto.append(bloco.path("text").asText());
        }
        return interpretar(texto.toString());
    }

    LeituraFolha interpretar(String resposta) throws Exception {
        return converter(json, resposta);
    }

    private static final ObjectMapper LEITOR_JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Extrai o JSON da resposta da IA (tolera ```json ... ``` ou texto em volta) e converte. */
    static LeituraFolha converter(ObjectMapper ignorado, String resposta) throws Exception {
        String s = resposta == null ? "" : resposta.trim();
        int ini = s.indexOf('{');
        int fim = s.lastIndexOf('}');
        if (ini < 0 || fim <= ini) {
            throw new RuntimeException("A leitura não retornou dados reconhecíveis. Tente outra foto, com mais luz.\n" + resposta);
        }
        LeituraFolha l = LEITOR_JSON.readValue(s.substring(ini, fim + 1), LeituraFolha.class);
        // garante uma nota por coluna em cada aluno (alguns modelos omitem as vazias)
        for (LeituraFolha.Item it : l.alunos) {
            if (it.notas == null) it.notas = new java.util.ArrayList<>();
            while (it.notas.size() < l.colunas.size()) it.notas.add("");
            for (int i = 0; i < it.notas.size(); i++) if (it.notas.get(i) == null) it.notas.set(i, "");
        }
        return l;
    }

    static String instrucoes() {
        return """
            Você lê fotos de folhas de notas de professores da rede estadual de Minas Gerais.
            A folha pode ser um caderno de registro manuscrito OU a folha impressa pelo programa
            "Lançador de Notas" (tem um código que começa com LND| no topo e nomes impressos).
            Pode estar torta, com sombra ou rasuras.

            Responda SOMENTE com um JSON válido, sem texto antes ou depois:
            {
              "codigoModelo": "LND|... exatamente como impresso, ou \"\"",
              "turma": "ex.: 9º A",
              "disciplina": "",
              "divisao": "ex.: 3º Trimestre",
              "colunas": [ {"nome": "Atv 1", "valor": 10.0}, {"nome": "Atv 2", "valor": 10.0} ],
              "alunos": [ {"numero": 1, "nome": "Ana Beatriz", "notas": ["8,0", "8,0"], "confianca": 0.95} ],
              "observacoes": ["coisas duvidosas que o professor deve conferir"]
            }

            Regras:
            - "colunas": TODAS as colunas de notas/atividades da folha, da esquerda para a direita, com o nome do
              cabeçalho (Atv 1, Atv 2, Atv 3, Recup., Avalia 1...). "valor" = o valor máximo escrito no cabeçalho
              ou na primeira linha (ex.: 10,0), ou null.
            - Cada aluno tem "notas" com EXATAMENTE um item por coluna, na mesma ordem.
            - Transcreva a nota como escrita ("8,0", "7,5", "10"). Célula vazia, traço, X, ✓ ou rabisco que não é
              número → "". Se estiver escrito NR (não realizada) → "NR".
            - "numero" = o Nº da linha. Linhas numeradas sem nome e sem nota: não inclua.
            - Na folha do programa os nomes são impressos: copie-os exatamente.
            - confianca de 0 a 1 por linha; abaixe se nome ou alguma nota estiver ilegível, rasurada ou deduzida
              (ex.: um 8 escrito por cima de outro número).
            - Nunca invente alunos ou notas. Mantenha a ordem da folha.
            """;
    }

    static String pedido(List<String> nomesDaTurma) {
        StringBuilder sb = new StringBuilder("Leia esta folha de notas.");
        if (nomesDaTurma != null && !nomesDaTurma.isEmpty()) {
            sb.append("\nLista oficial de alunos desta turma no sistema (use para identificar nomes mal escritos, ")
              .append("mas transcreva o nome como aparece na folha):\n");
            for (String n : nomesDaTurma) sb.append("- ").append(n.toUpperCase(Locale.ROOT)).append('\n');
        }
        return sb.toString();
    }
}
