package br.com.lancadornotas.util;

import br.com.lancadornotas.model.Aluno;
import br.com.lancadornotas.model.Avaliacao;
import br.com.lancadornotas.model.Notas;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Gera a folha de notas para imprimir (HTML A4, imprime direto do navegador ou salva em PDF).
 *
 * Feita para a leitura ser fácil e segura:
 *  - nomes IMPRESSOS na ordem do DED+ (o professor só escreve a nota);
 *  - uma coluna por avaliação, na mesma ordem do DED+ (Atv 1 = 1ª avaliação...);
 *  - código "LND|escola|turma|divisão" no topo de cada página, para o programa saber de onde é a folha;
 *  - quadros grandes, bordas fortes e listras leves para a foto do celular.
 */
public final class FolhaModelo {

    public static final int LINHAS_EM_BRANCO = 35;
    public static final int COLUNAS_EM_BRANCO = 4;

    private FolhaModelo() {}

    public record Dados(String escola, String codigoEscola, String turma, String divisao,
                        List<Avaliacao> avaliacoes, List<Aluno> alunos) {}

    /**
     * Código curto impresso na folha: LND-&lt;código da escola&gt;-&lt;6 letras que identificam turma+divisão&gt;.
     * Curto de propósito, para a leitura da foto não errar. Folha em branco: "LND-BRANCO".
     */
    public static String codigo(Dados d) {
        if (d.turma() == null || d.turma().isBlank()) return "LND-BRANCO";
        String chave = nz(d.codigoEscola()) + "|" + nz(d.turma()) + "|" + nz(d.divisao());
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(chave.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String hex = java.util.HexFormat.of().withUpperCase().formatHex(h).substring(0, 6);
            return "LND-" + (nz(d.codigoEscola()).isEmpty() ? "0" : nz(d.codigoEscola())) + "-" + hex;
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String html(Dados d) {
        boolean emBranco = d.alunos() == null || d.alunos().isEmpty();
        List<Avaliacao> avs = d.avaliacoes() == null ? List.of() : d.avaliacoes();
        List<String[]> colunas = new ArrayList<>(); // {titulo, nome, valor}
        if (avs.isEmpty()) {
            for (int i = 1; i <= COLUNAS_EM_BRANCO; i++) colunas.add(new String[]{"ATV " + i, "", ""});
        } else {
            for (int i = 0; i < avs.size(); i++) {
                Avaliacao a = avs.get(i);
                colunas.add(new String[]{"ATV " + (i + 1), a.nome(),
                        a.valor() != null ? "vale " + Notas.formatar(a.valor()) : ""});
            }
        }
        int nLinhas = emBranco ? LINHAS_EM_BRANCO : d.alunos().size();
        // ~235 mm úteis para as linhas numa página A4 (descontando cabeçalho na 1ª página)
        double alturaLinha = Math.max(6.0, Math.min(9.0, 203.0 / Math.max(nLinhas, 1)));
        double larguraNota = colunas.size() <= 4 ? 22 : colunas.size() <= 6 ? 18 : 14;

        StringBuilder h = new StringBuilder(16_000);
        h.append("""
            <!DOCTYPE html>
            <html lang="pt-BR"><head><meta charset="utf-8">
            <title>Folha de notas</title>
            <style>
              @page { size: A4 portrait; margin: 9mm 9mm 10mm 9mm; }
              * { box-sizing: border-box; }
              body { font-family: Arial, Helvetica, sans-serif; color: #000; margin: 0; }
              .barra { display: flex; justify-content: space-between; align-items: flex-end;
                       border-bottom: 2.5px solid #000; padding-bottom: 2mm; margin-bottom: 2.5mm; }
              h1 { font-size: 15pt; margin: 0; letter-spacing: .5px; white-space: nowrap; }
              .sub { font-size: 9pt; color: #333; margin-top: 1mm; }
              .codigo { font-family: "Courier New", monospace; font-size: 10pt; font-weight: bold;
                        border: 2px solid #000; padding: 1.2mm 2.5mm; white-space: nowrap; }
              .info { display: grid; grid-template-columns: 3fr 1.2fr; gap: 1.2mm 5mm; font-size: 9.5pt; margin-bottom: 2mm; }
              .info b { display: inline-block; min-width: 16mm; }
              .linha { border-bottom: 1px solid #000; display: inline-block; min-width: 45mm; }
              .instr { display: flex; gap: 4mm; align-items: center; border: 1.5px solid #000;
                       padding: 1.2mm 3mm; font-size: 8.5pt; margin-bottom: 2.5mm; }
              .instr ul { margin: 0; padding-left: 4mm; }
              .exemplo { border: 2px solid #000; width: 16mm; height: 9mm; display: flex; align-items: center;
                         justify-content: center; font: italic 15pt "Comic Sans MS", "Segoe Print", cursive; flex: none; }
              table { width: 100%; border-collapse: collapse; table-layout: fixed; }
              thead { display: table-header-group; }
              th, td { border: 1.6px solid #000; }
              th { background: #e6e6e6; font-size: 8pt; padding: 1mm .8mm; vertical-align: bottom; }
              th .t { font-size: 11pt; display: block; }
              th .n { display: block; font-weight: normal; font-size: 6.5pt; line-height: 1.1; max-height: 3.4em; overflow: hidden; hyphens: auto; }
              th .v { display: block; font-weight: normal; font-style: italic; }
              tr.cod th { background: #fff; border: none; text-align: right; font: bold 8pt "Courier New", monospace; padding: 0 0 1mm; }
              td { font-size: 9.5pt; padding: 0 1.5mm; }
              td.num { text-align: center; font-weight: bold; font-size: 10.5pt; }
              td.nome { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
              td.mat { font-size: 7pt; color: #444; }
              td.nota { background: #fff; }
              tbody tr:nth-child(even) td.num, tbody tr:nth-child(even) td.nome { background: #f0f0f0; }
              tbody tr:nth-child(5n) td { border-bottom-width: 2.6px; }
              .rodape { font-size: 7.5pt; color: #444; margin-top: 2mm; display: flex; justify-content: space-between; }
              @media screen { body { width: 210mm; margin: 8mm auto; padding: 9mm; box-shadow: 0 0 6px #999; }
                              .imprimir { position: fixed; top: 10px; right: 10px; font-size: 14px; padding: 8px 14px; } }
              @media print { .imprimir { display: none; } }
            </style></head><body>
            <button class="imprimir" onclick="window.print()">Imprimir</button>
            """);

        h.append("<div class='barra'><div><h1>REGISTRO DE NOTAS</h1><div class='sub'>")
         .append(emBranco ? "Folha em branco — escreva os nomes e as notas"
                          : "Escreva somente as notas. Os nomes já estão na ordem do DED+.")
         .append("</div></div><div class='codigo'>").append(esc(codigo(d))).append("</div></div>");

        h.append("<div class='info'>")
         .append("<div><b>Escola:</b> ").append(campo(d.escola())).append("</div>")
         .append("<div><b>Divisão:</b> ").append(campo(d.divisao())).append("</div>")
         .append("<div><b>Turma:</b> ").append(campo(d.turma())).append("</div>")
         .append("<div><b>Data:</b> <span class='linha' style='min-width:28mm'></span></div>")
         .append("<div><b>Professor(a):</b> <span class='linha' style='min-width:80mm'></span></div>")
         .append("</div>");

        h.append("""
            <div class="instr"><div class="exemplo">8,5</div><ul>
              <li>Escreva <b>só a nota</b>, com algarismos grandes e dentro do quadro. Use vírgula: <b>7,5</b></li>
              <li>Deixe <b>em branco</b> o que não for lançar. Aluno que não fez a atividade: escreva <b>NR</b>.</li>
              <li>Errou? Risque o quadro inteiro e escreva a nota certa ao lado, bem clara.</li>
            </ul></div>
            """);

        // cabeçalho da tabela (repete em cada página impressa, com o código)
        int nCols = colunas.size();
        h.append("<table><colgroup><col style='width:9mm'>")
         .append(emBranco ? "" : "<col style='width:17mm'>")
         .append("<col>");
        for (int i = 0; i < nCols; i++) h.append("<col style='width:").append(larguraNota).append("mm'>");
        h.append("</colgroup><thead>");
        h.append("<tr class='cod'><th colspan='").append(nCols + (emBranco ? 2 : 3)).append("'>")
         .append(esc(codigo(d))).append("</th></tr>");
        h.append("<tr><th>Nº</th>").append(emBranco ? "" : "<th>Matrícula</th>").append("<th style='text-align:left'>ALUNO</th>");
        for (String[] c : colunas) {
            h.append("<th><span class='t'>").append(esc(c[0])).append("</span>");
            if (!c[1].isEmpty()) h.append("<span class='n'>").append(esc(c[1])).append("</span>");
            h.append("<span class='v'>").append(esc(c[2])).append("</span></th>");
        }
        h.append("</tr></thead><tbody>");

        for (int i = 0; i < nLinhas; i++) {
            h.append("<tr style='height:").append(String.format(java.util.Locale.ROOT, "%.1f", alturaLinha)).append("mm'>");
            h.append("<td class='num'>").append(String.format("%02d", i + 1)).append("</td>");
            if (!emBranco) {
                Aluno a = d.alunos().get(i);
                h.append("<td class='mat'>").append(esc(a.matricula())).append("</td>");
                h.append("<td class='nome'>").append(esc(a.nome())).append("</td>");
            } else {
                h.append("<td class='nome'></td>");
            }
            for (int c = 0; c < nCols; c++) h.append("<td class='nota'></td>");
            h.append("</tr>");
        }
        h.append("</tbody></table>");

        h.append("<div class='rodape'><span>Lançador de Notas · gerada em ")
         .append(LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
         .append(emBranco ? "" : " · " + nLinhas + " alunos")
         .append("</span><span>Para fotografar: folha inteira, de cima, com boa luz e sem sombra.</span></div>");
        h.append("</body></html>");
        return h.toString();
    }

    private static String campo(String s) {
        return (s == null || s.isBlank()) ? "<span class='linha'></span>" : esc(s);
    }

    private static String nz(String s) { return s == null ? "" : s.replace("|", "/").trim(); }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
