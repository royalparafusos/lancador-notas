# Política de privacidade — Lançador de Notas (Conector do DED+)

Última atualização: 26/11/2026

O **Lançador de Notas — Conector do DED+** é uma extensão de navegador que liga o programa
*Lançador de Notas*, instalado no computador do professor, ao DED+ (Diário Escolar Digital de Minas Gerais)
que o próprio professor abriu e acessou com o seu login.

## O que a extensão faz

- Funciona **somente** nas páginas do DED+ (`dedmais.educacao.mg.gov.br`) e do login Seg.ID (`ssc.prodemge.gov.br`).
- Recebe ordens do programa Lançador de Notas, que roda **no mesmo computador** (endereço `127.0.0.1`),
  e faz na página o que o professor faria: abrir a turma, ler os nomes e matrículas dos alunos e as avaliações,
  digitar as notas e conferir o que o DED+ gravou.

## Dados

- Os nomes, matrículas, avaliações e notas lidos no DED+ são enviados **apenas** ao programa Lançador de Notas
  no próprio computador. A extensão **não envia nada para servidores na internet** e não tem servidor próprio.
- A extensão **não lê nem guarda senhas**. Na tela de login do Seg.ID ela fica parada; o professor entra sozinho.
- Nenhum dado é vendido, compartilhado com terceiros ou usado para publicidade.
- A extensão não usa cookies, rastreamento nem análise de uso.

## Permissões

- **Acesso a dedmais.educacao.mg.gov.br e ssc.prodemge.gov.br**: para trabalhar nas páginas do DED+.
- **Acesso a 127.0.0.1:47321**: para conversar com o programa Lançador de Notas no próprio computador.
- **scripting**: para ativar o conector nas abas do DED+ que já estavam abertas quando a extensão é instalada ou atualizada.

## Contato

Douglas Mendes Homem — dúvidas pelo GitHub: https://github.com/royalparafusos/lancador-notas/issues
