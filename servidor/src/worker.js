// =====================================================================
//  Lançador de Notas — servidor de cobrança (Cloudflare Worker)
//
//  • /pagamento   cria o pagamento no Mercado Pago (Checkout Pro: Pix, cartão, boleto)
//  • /webhook     o Mercado Pago avisa aqui quando um pagamento muda; o servidor CONFERE
//                 direto na API do Mercado Pago (aviso falso não libera nada)
//  • /licenca     diz se o e-mail do DED+ tem licença e devolve um "comprovante" assinado
//  • /ocr         leitura das fotos (Groq) — só para quem tem licença; a chave fica aqui
//  • /admin/...   liberar / ver / zerar computadores (só com a senha de administrador)
//
//  Segredos (wrangler secret put ...): MP_ACCESS_TOKEN, GROQ_KEY, LICENCA_CHAVE_PRIVADA, ADMIN_KEY
//  Variáveis (wrangler.toml): PRECO_MENSAL, PRECO_ANUAL, MAX_COMPUTADORES, EMAILS_LIVRES
//  Dados (KV "LICENCAS"): lic:<email> -> { email, validoAte, plano, maquinas[], pagamentos[] }
//                         pag:<id>    -> pagamento já processado
// =====================================================================

const DIA = 24 * 60 * 60 * 1000;
const PLANOS = {
  mensal: { titulo: 'Lançador de Notas — 1 mês', dias: 31 },
  anual: { titulo: 'Lançador de Notas — 1 ano', dias: 366 },
};

const json = (obj, status = 200) =>
  new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json; charset=utf-8' } });
const normEmail = e => String(e || '').trim().toLowerCase();
const emailValido = e => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(e);
const mpApi = env => env.MP_API || 'https://api.mercadopago.com';
const preco = (env, plano) => Number(plano === 'anual' ? env.PRECO_ANUAL || 149.9 : env.PRECO_MENSAL || 19.9);

// ------------------------------------------------------------------ comprovante assinado (Ed25519)
const b64u = buf => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const deB64u = s => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4)), c => c.charCodeAt(0));
let chavePriv = null, chavePub = null;
async function chaves(env) {
  if (!chavePriv) {
    const jwk = JSON.parse(env.LICENCA_CHAVE_PRIVADA); // {kty:"OKP",crv:"Ed25519",d,x}
    chavePriv = await crypto.subtle.importKey('jwk', jwk, { name: 'Ed25519' }, false, ['sign']);
    chavePub = await crypto.subtle.importKey('jwk', { kty: 'OKP', crv: 'Ed25519', x: jwk.x }, { name: 'Ed25519' }, false, ['verify']);
  }
  return { chavePriv, chavePub };
}
async function assinar(env, dados) {
  const corpo = b64u(new TextEncoder().encode(JSON.stringify(dados)));
  const sig = await crypto.subtle.sign({ name: 'Ed25519' }, (await chaves(env)).chavePriv, new TextEncoder().encode(corpo));
  return corpo + '.' + b64u(sig);
}
async function conferirComprovante(env, token) {
  try {
    const [corpo, sig] = String(token || '').split('.');
    const ok = await crypto.subtle.verify({ name: 'Ed25519' }, (await chaves(env)).chavePub, deB64u(sig), new TextEncoder().encode(corpo));
    if (!ok) return null;
    const d = JSON.parse(new TextDecoder().decode(deB64u(corpo)));
    return d.validoAte > Date.now() ? d : null;
  } catch (e) { return null; }
}

// ------------------------------------------------------------------ licenças
async function lerLicenca(env, email) {
  const l = await env.LICENCAS.get('lic:' + email, 'json');
  return l || { email, validoAte: 0, plano: null, maquinas: [], pagamentos: [] };
}
const livre = (env, email) => String(env.EMAILS_LIVRES || '').toLowerCase().split(',').map(s => s.trim()).filter(Boolean).includes(email);

/** Status da licença; registra o computador (até MAX_COMPUTADORES por e-mail). */
async function status(env, email, maquina) {
  const l = await lerLicenca(env, email);
  let validoAte = l.validoAte;
  if (livre(env, email)) validoAte = Math.max(validoAte, Date.now() + 365 * DIA);
  const ativo = validoAte > Date.now();
  const resp = { email, ativo, validoAte, plano: livre(env, email) ? 'cortesia' : l.plano,
                 precoMensal: preco(env, 'mensal'), precoAnual: preco(env, 'anual') };
  if (!ativo) return resp;
  const max = Number(env.MAX_COMPUTADORES || 2);
  if (maquina && !l.maquinas.includes(maquina)) {
    if (l.maquinas.length >= max) {
      return { ...resp, ativo: false, bloqueio: 'computadores',
        mensagem: 'Esta licença já está em uso em ' + max + ' computadores. Ela é pessoal: cada professor precisa da sua. '
                + 'Se trocou de computador, fale com o suporte para liberar.' };
    }
    l.maquinas.push(maquina);
    await env.LICENCAS.put('lic:' + email, JSON.stringify(l));
  }
  // comprovante vale até o fim da licença (no máx. 7 dias; depois o programa pede outro)
  const ate = Math.min(validoAte, Date.now() + 7 * DIA);
  resp.comprovante = await assinar(env, { email, maquina: maquina || null, validoAte: ate, licencaAte: validoAte });
  return resp;
}

async function estender(env, email, dias, plano, pagamentoId) {
  const l = await lerLicenca(env, email);
  const base = Math.max(l.validoAte, Date.now());
  l.validoAte = base + dias * DIA;
  l.plano = plano;
  if (pagamentoId) l.pagamentos = [...(l.pagamentos || []), String(pagamentoId)].slice(-50);
  await env.LICENCAS.put('lic:' + email, JSON.stringify(l));
  return l;
}

// ------------------------------------------------------------------ Mercado Pago
async function criarPagamento(env, origem, email, plano) {
  const p = PLANOS[plano];
  const ref = ['LND', email, plano, Date.now().toString(36)].join('|');
  const r = await fetch(mpApi(env) + '/checkout/preferences', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + env.MP_ACCESS_TOKEN, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      items: [{ id: 'lnd-' + plano, title: p.titulo, description: 'Licença para o e-mail do DED+ ' + email,
                quantity: 1, currency_id: 'BRL', unit_price: preco(env, plano) }],
      external_reference: ref,
      metadata: { email, plano },
      notification_url: origem + '/webhook',
      back_urls: { success: origem + '/obrigado', pending: origem + '/obrigado', failure: origem + '/obrigado' },
      auto_return: 'approved',
      statement_descriptor: 'LANCADORNOTAS',
    }),
  });
  const d = await r.json();
  if (!r.ok || !d.init_point) throw new Error('Mercado Pago recusou: ' + (d.message || r.status));
  return { link: d.init_point, referencia: ref };
}

/** Confere um pagamento direto no Mercado Pago e libera a licença se estiver aprovado. */
async function processarPagamento(env, id) {
  if (!/^\d+$/.test(String(id))) return { ignorado: 'id inválido' };
  if (await env.LICENCAS.get('pag:' + id)) return { ja: true };
  const r = await fetch(mpApi(env) + '/v1/payments/' + id, { headers: { Authorization: 'Bearer ' + env.MP_ACCESS_TOKEN } });
  if (!r.ok) return { ignorado: 'consulta ' + r.status };
  const pg = await r.json();
  if (pg.status !== 'approved') return { status: pg.status };
  const [tag, email, plano] = String(pg.external_reference || '').split('|');
  if (tag !== 'LND' || !PLANOS[plano] || !emailValido(email)) return { ignorado: 'referência desconhecida' };
  if (Number(pg.transaction_amount) + 0.001 < preco(env, plano)) return { ignorado: 'valor menor que o plano' };
  await env.LICENCAS.put('pag:' + id, JSON.stringify({ email, plano, valor: pg.transaction_amount, em: Date.now() }));
  const l = await estender(env, email, PLANOS[plano].dias, plano, id);
  return { liberado: email, validoAte: l.validoAte };
}

// ------------------------------------------------------------------ leitura das fotos (Groq)
async function ocr(env, req) {
  const dados = await conferirComprovante(env, req.headers.get('X-Licenca'));
  if (!dados) return json({ erro: 'Licença inválida ou vencida. Abra "Assinatura" no programa.' }, 402);
  if (dados.maquina && dados.maquina !== req.headers.get('X-Maquina')) return json({ erro: 'Licença de outro computador.' }, 403);
  // repassa o pedido como veio (sem abrir a foto: rápido e barato)
  const r = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + env.GROQ_KEY, 'Content-Type': 'application/json' },
    body: req.body,
  });
  return new Response(r.body, { status: r.status, headers: { 'Content-Type': 'application/json', 'Retry-After': r.headers.get('Retry-After') || '' } });
}

const PAGINA_OBRIGADO = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Lançador de Notas</title><body style="font-family:Segoe UI,Arial;max-width:560px;margin:60px auto;text-align:center;color:#333">
<h1 style="color:#2e7d32">Obrigado!</h1><p style="font-size:19px">Assim que o Mercado Pago confirmar o pagamento, o programa
<b>Lançador de Notas</b> libera sozinho. Pode fechar esta página e voltar para o programa.</p>
<p style="color:#777">Pix e cartão: na hora. Boleto: até 2 dias úteis.</p></body>`;

// ------------------------------------------------------------------ rotas
export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const caminho = url.pathname.replace(/\/+$/, '') || '/';
    try {
      if (caminho === '/licenca' && req.method === 'POST') {
        const b = await req.json();
        const email = normEmail(b.email);
        if (!emailValido(email)) return json({ erro: 'E-mail do DED+ inválido.' }, 400);
        return json(await status(env, email, String(b.maquina || '').slice(0, 80)));
      }
      if (caminho === '/pagamento' && req.method === 'POST') {
        const b = await req.json();
        const email = normEmail(b.email);
        if (!emailValido(email)) return json({ erro: 'E-mail do DED+ inválido.' }, 400);
        if (!PLANOS[b.plano]) return json({ erro: 'Plano inválido.' }, 400);
        return json(await criarPagamento(env, url.origin, email, b.plano));
      }
      if (caminho === '/webhook') {
        // o Mercado Pago manda o id pela URL (?data.id= / ?id=) e/ou no corpo
        let id = url.searchParams.get('data.id') || url.searchParams.get('id');
        let tipo = url.searchParams.get('type') || url.searchParams.get('topic');
        if (req.method === 'POST') {
          try { const b = await req.json(); id = (b.data && b.data.id) || id; tipo = b.type || b.topic || tipo; } catch (e) { /* sem corpo */ }
        }
        if (id && (!tipo || tipo === 'payment')) await processarPagamento(env, id);
        return new Response('ok', { status: 200 });
      }
      if (caminho === '/ocr' && req.method === 'POST') return ocr(env, req);
      if (caminho === '/obrigado') return new Response(PAGINA_OBRIGADO, { headers: { 'Content-Type': 'text/html; charset=utf-8' } });

      if (caminho.startsWith('/admin/')) {
        if (!env.ADMIN_KEY || req.headers.get('X-Admin') !== env.ADMIN_KEY) return json({ erro: 'não autorizado' }, 401);
        const b = req.method === 'POST' ? await req.json() : Object.fromEntries(url.searchParams);
        const email = normEmail(b.email);
        if (caminho === '/admin/ver') return json(await lerLicenca(env, email));
        if (caminho === '/admin/liberar') return json(await estender(env, email, Number(b.dias || 31), b.plano || 'cortesia', null));
        if (caminho === '/admin/zerar-computadores') {
          const l = await lerLicenca(env, email); l.maquinas = [];
          await env.LICENCAS.put('lic:' + email, JSON.stringify(l)); return json(l);
        }
        if (caminho === '/admin/conferir-pagamento') return json(await processarPagamento(env, b.id));
        if (caminho === '/admin/lista') {
          const lista = await env.LICENCAS.list({ prefix: 'lic:' });
          return json(lista.keys.map(k => k.name.slice(4)));
        }
      }
      if (caminho === '/') return new Response('Lançador de Notas — servidor de licenças no ar.');
      return json({ erro: 'não encontrado' }, 404);
    } catch (e) {
      return json({ erro: String((e && e.message) || e) }, 500);
    }
  },
};
