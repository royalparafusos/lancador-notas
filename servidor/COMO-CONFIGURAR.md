# Servidor de cobrança — como colocar no ar (uma vez só)

O servidor roda de graça na Cloudflare. Ele guarda as chaves secretas (Mercado Pago e Groq),
confirma os pagamentos e libera a licença para o e-mail do DED+ do professor.

## 1. Contas e chaves

1. **Cloudflare** (grátis): crie a conta em https://dash.cloudflare.com/sign-up
2. **Mercado Pago**: em https://www.mercadopago.com.br/developers/panel/app crie uma aplicação
   ("Pagamentos online", produto "Checkout Pro"). Em **Credenciais de produção**, copie o **Access Token**
   (começa com `APP_USR-`). Para testar antes, use as **Credenciais de teste**.
3. **Groq**: a sua chave `gsk_...` (a mesma de hoje). Ela passa a ficar só no servidor.

## 2. Publicar (no computador com Node.js, dentro da pasta `servidor`)

```
npm install
npx wrangler login                              (abre o navegador para entrar na Cloudflare)
npx wrangler kv namespace create LICENCAS        (copie o "id" e cole no wrangler.toml)
npx wrangler secret put MP_ACCESS_TOKEN          (cole o Access Token do Mercado Pago)
npx wrangler secret put GROQ_KEY                 (cole a chave gsk_...)
npx wrangler secret put ADMIN_KEY                (invente uma senha forte de administrador)
npx wrangler secret put LICENCA_CHAVE_PRIVADA    (cole o conteúdo do arquivo licenca-chave-privada.json)
npx wrangler deploy
```

O `deploy` mostra o endereço, algo como `https://lancador-notas.SEU-NOME.workers.dev`.
Mande esse endereço para o Claude colocar no programa — a partir daí a cobrança liga para todos.

> O arquivo `licenca-chave-privada.json` é SECRETO (quem tiver ele consegue fabricar licenças).
> Não coloque no GitHub nem mande para ninguém. A chave pública correspondente já está no programa.

## 3. Mercado Pago: avisos de pagamento

O servidor já informa ao Mercado Pago o endereço de aviso em cada pagamento (`.../webhook`).
Opcional: em *Suas integrações > Webhooks*, cadastre também `https://SEU-ENDERECO/webhook` (evento *Pagamentos*).
Cada aviso é conferido direto na API do Mercado Pago: aviso falso não libera nada.

## 4. Administração (no terminal ou no navegador com uma extensão de requisições)

Troque `SENHA` pela ADMIN_KEY e `EMAIL` pelo e-mail do DED+:

```
# ver a licença de um professor
curl -H "X-Admin: SENHA" "https://SEU-ENDERECO/admin/ver?email=EMAIL"
# liberar dias de cortesia (ex.: 31 dias)
curl -X POST -H "X-Admin: SENHA" -d "{\"email\":\"EMAIL\",\"dias\":31}" https://SEU-ENDERECO/admin/liberar
# professor trocou de computador: zerar os computadores
curl -X POST -H "X-Admin: SENHA" -d "{\"email\":\"EMAIL\"}" https://SEU-ENDERECO/admin/zerar-computadores
# pagamento aprovado que não liberou: conferir pelo número do pagamento
curl -X POST -H "X-Admin: SENHA" -d "{\"id\":\"123456789\"}" https://SEU-ENDERECO/admin/conferir-pagamento
# lista de e-mails com licença
curl -H "X-Admin: SENHA" https://SEU-ENDERECO/admin/lista
```

Seu próprio e-mail (e de quem você quiser liberar sem pagar): coloque em `EMAILS_LIVRES` no `wrangler.toml`
(separados por vírgula) e rode `npx wrangler deploy` de novo.

## Regras

- Mensal: R$ 19,90 → 31 dias. Anual: R$ 149,90 → 366 dias. Pagar de novo soma ao tempo que falta.
  (preços em `PRECO_MENSAL` / `PRECO_ANUAL` no `wrangler.toml`)
- A licença é do e-mail do DED+ lido pelo programa no DED+ aberto (o professor não digita).
- Até 2 computadores por e-mail (`MAX_COMPUTADORES`).
- Sem internet, o programa continua liberado até 7 dias com o último comprovante.
- A leitura das fotos só funciona com licença válida (a chave do Groq não fica mais no instalador).
- Plano gratuito da Cloudflare: 100 mil acessos por dia e 1 mil gravações por dia (sobra para milhares de professores).
