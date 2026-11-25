package br.com.lancadornotas.ocr;

import br.com.lancadornotas.Config;
import br.com.lancadornotas.model.LeituraFolha;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * Lê a folha com o Groq (plano GRATUITO), modelo de visão qwen/qwen3.8-27b.
 *
 * Limites do plano gratuito (por ORGANIZAÇÃO Groq, ou seja, somados entre todos que usam a mesma chave):
 * cerca de 8 mil tokens por minuto e 200 mil por dia. Cada foto gasta uns 5–6 mil tokens,
 * então dá ~1 foto por minuto e ~35 fotos por dia por chave.
 *
 * Endpoint compatível com OpenAI: https://api.groq.com/openai/v1/chat/completions
 */
public class LeitorFolhaGroq implements LeitorFolha {

    private static final String URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final long LIMITE_BYTES = 3_000_000; // folga para o base64 (~4 MB)

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public LeituraFolha ler(Path foto, List<String> nomesDaTurma) throws Exception {
        String chave = Config.chaveGroq();
        if (chave.isBlank()) {
            throw new IllegalStateException("O programa ainda não tem a chave de leitura de fotos. "
                    + "Peça a quem instalou o programa para configurar (botão Configurações).");
        }
        byte[] bytes = ImagemUtil.prepararParaEnvio(foto, LIMITE_BYTES);
        String corpo = montarCorpo(Base64.getEncoder().encodeToString(bytes), nomesDaTurma);

        HttpResponse<String> resp = enviar(chave, corpo);
        if (resp.statusCode() == 429) {
            // limite por minuto do plano gratuito: espera o tempo que o Groq pedir (até 65 s) e tenta 1 vez
            long espera = resp.headers().firstValue("retry-after").map(LeitorFolhaGroq::segundos).orElse(30L);
            if (espera <= 65) {
                Thread.sleep((espera + 1) * 1000);
                resp = enviar(chave, corpo);
            }
            if (resp.statusCode() == 429) {
                throw new IllegalStateException("O limite GRATUITO de leitura foi atingido. "
                        + "Espere alguns minutos e tente de novo. Se acontecer sempre, o limite do dia acabou "
                        + "(ele volta amanhã).");
            }
        }
        if (resp.statusCode() == 401) {
            throw new IllegalStateException("A chave do Groq não foi aceita. Confira em Configurações.");
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Erro do serviço de leitura Groq (" + resp.statusCode() + "): " + resp.body());
        }
        return interpretar(resp.body());
    }

    private HttpResponse<String> enviar(String chave, String corpo) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + chave)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    String montarCorpo(String base64Jpeg, List<String> nomesDaTurma) throws Exception {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", Config.modeloGroq());
        corpo.put("temperature", 0);
        corpo.put("max_completion_tokens", 4096);
        corpo.putObject("response_format").put("type", "json_object");

        ArrayNode msgs = corpo.putArray("messages");
        ObjectNode sys = msgs.addObject();
        sys.put("role", "system");
        sys.put("content", LeitorFolhaClaude.instrucoes());

        ObjectNode user = msgs.addObject();
        user.put("role", "user");
        ArrayNode content = user.putArray("content");
        ObjectNode txt = content.addObject();
        txt.put("type", "text");
        txt.put("text", LeitorFolhaClaude.pedido(nomesDaTurma) + "\nResponda somente com o JSON.");
        ObjectNode img = content.addObject();
        img.put("type", "image_url");
        img.putObject("image_url").put("url", "data:image/jpeg;base64," + base64Jpeg);

        return json.writeValueAsString(corpo);
    }

    LeituraFolha interpretar(String respostaHttp) throws Exception {
        JsonNode raiz = json.readTree(respostaHttp);
        String texto = raiz.path("choices").path(0).path("message").path("content").asText("");
        // alguns modelos "pensam alto" antes da resposta: descarta esse trecho
        texto = texto.replaceAll("(?s)<think>.*?</think>", "");
        return LeitorFolhaClaude.converter(json, texto);
    }

    private static long segundos(String v) {
        try { return (long) Math.ceil(Double.parseDouble(v.trim())); } catch (Exception e) { return 30L; }
    }
}
