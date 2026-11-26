# Textos para cadastrar o conector nas lojas (Chrome Web Store e Edge Add-ons)

**Pacote:** `conector-ded.zip`, anexado a cada versão em https://github.com/royalparafusos/lancador-notas/releases/latest
**Visibilidade:** *Não listado* (só abre por quem tem o link; não aparece nas buscas).
**Categoria:** Produtividade (Chrome) / Produtividade (Edge). **Idioma:** Português (Brasil).

## Nome
Lançador de Notas — Conector do DED+

## Resumo (até 132 caracteres)
Liga o programa Lançador de Notas ao DED+ aberto no navegador, para lançar as notas da folha fotografada.

## Descrição
Conector do programa Lançador de Notas, para professores da rede estadual de Minas Gerais.

Como funciona:
1. No programa Lançador de Notas, o professor imprime a lista da turma (nomes e avaliações vindos do DED+).
2. Escreve as notas à mão e tira uma foto da folha.
3. O programa lê a foto e, por meio deste conector, lança as notas no DED+ que o próprio professor abriu e acessou com o seu login.

Privacidade:
• Funciona somente nas páginas do DED+ e do login Seg.ID.
• Conversa apenas com o programa Lançador de Notas no próprio computador (127.0.0.1). Não envia dados para a internet.
• Não lê nem guarda senhas: o professor entra no DED+ sozinho.

Este conector precisa do programa Lançador de Notas instalado no computador.

## Justificativas de permissão (Chrome pede no formulário de "Privacidade")
- **Finalidade única:** lançar no DED+ as notas que o programa Lançador de Notas leu da folha do professor.
- **scripting:** ativar o conector nas abas do DED+ que já estavam abertas quando ele é instalado ou atualizado.
- **Permissão de host (dedmais.educacao.mg.gov.br, ssc.prodemge.gov.br):** ler as turmas, alunos e avaliações e digitar as notas nas páginas do DED+.
- **Permissão de host (127.0.0.1:47321):** conversar com o programa Lançador de Notas no próprio computador.
- **Código remoto:** Não, não uso código remoto.
- **Dados coletados:** marcar "Informações pessoais" e "Conteúdo do site" (nomes, matrículas e notas lidos no DED+), usados só para a finalidade única e não transferidos a terceiros. Marcar as três declarações (não vendo, não uso para outros fins, não uso para crédito).

## Política de privacidade (endereço)
https://github.com/royalparafusos/lancador-notas/blob/main/docs/privacidade.md

## Imagens
- Ícone 128×128: `src/main/resources/extensao/icone.png`
- Captura de tela 1280×800: `docs/loja/captura-1280x800.png`
- Bloco promocional pequeno 440×280: `docs/loja/promo-440x280.png`

## Depois de aprovado
Mande os links da loja para o Claude colocar no programa (botões "Instalar no Google Chrome" / "Instalar no Microsoft Edge").
Para as próximas versões publicarem sozinhas, cadastre os segredos no GitHub (Settings > Secrets and variables > Actions):
`CWS_EXTENSION_ID`, `CWS_CLIENT_ID`, `CWS_CLIENT_SECRET`, `CWS_REFRESH_TOKEN` (Chrome) e
`EDGE_PRODUCT_ID`, `EDGE_CLIENT_ID`, `EDGE_API_KEY` (Edge).
