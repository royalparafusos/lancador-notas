// Lançador de Notas — ponte entre as páginas do DED+ e o programa (só no próprio computador).
// As ordens do programa chegam por http://127.0.0.1:47321 e vão para UMA aba do DED+ (a "principal").
// token.js só existe quando o conector é carregado da pasta do programa (modo desenvolvedor).
// Instalado pela loja (Chrome Web Store / Edge), não há token: o programa reconhece a extensão pela origem.
try { importScripts('token.js'); } catch (e) { /* instalado pela loja */ }
const CHAVE = (typeof TOKEN !== 'undefined') ? TOKEN : '';

const PROGRAMA = 'http://127.0.0.1:47321';
const abas = {};            // id -> { visto: ms, visivel: bool, url }
let principal = null;

function escolherPrincipal() {
  const agora = Date.now();
  for (const id of Object.keys(abas)) if (agora - abas[id].visto > 6000) delete abas[id];
  if (principal !== null && !abas[principal]) principal = null;
  // prefere uma aba visível; senão mantém a atual; senão qualquer uma
  const visiveis = Object.keys(abas).filter(id => abas[id].visivel).map(Number);
  if (principal === null || (!abas[principal].visivel && visiveis.length)) {
    principal = visiveis.length ? visiveis[0] : (Object.keys(abas).length ? Number(Object.keys(abas)[0]) : null);
  }
}

chrome.runtime.onMessage.addListener((msg, sender, responder) => {
  const aba = sender.tab ? sender.tab.id : null;
  if (msg.tipo === 'buscar') {
    abas[aba] = { visto: Date.now(), visivel: !!msg.visivel, url: msg.url };
    escolherPrincipal();
    if (aba !== principal) { responder(null); return; }
    const q = '?url=' + encodeURIComponent(msg.url) + '&ext=' + chrome.runtime.getManifest().version;
    fetch(PROGRAMA + '/proximo' + q, { method: 'POST', cache: 'no-store', headers: { 'X-Token': CHAVE } })
      .then(r => (r.status === 200 ? r.json() : null))
      .then(cmd => responder(cmd))
      .catch(() => responder(null));
    return true; // resposta assíncrona
  }
  if (msg.tipo === 'resposta' || msg.tipo === 'progresso') {
    fetch(PROGRAMA + '/' + msg.tipo, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Token': CHAVE },
      body: JSON.stringify(msg.dados)
    }).then(() => responder(true)).catch(() => responder(false));
    return true;
  }
});

// ---------------------------------------------------------------------------------------------
//  A parte que mexe no DED+ (conteudo.js) NÃO fica presa na extensão: é colocada em cada página
//  do DED+ quando ela abre, lida da pasta na hora. Assim, quando o programa se atualiza e grava um
//  conteudo.js novo na pasta, basta abrir/atualizar o DED+ — não precisa clicar no ↻ da extensão.
// ---------------------------------------------------------------------------------------------
const DED = /^https:\/\/(dedmais\.educacao\.mg\.gov\.br|ssc\.prodemge\.gov\.br)\//;

async function injetar(tabId) {
  try {
    const [r] = await chrome.scripting.executeScript({ target: { tabId }, func: () => !!window.__lndConector });
    if (r && r.result) return; // já está rodando nesta página
    await chrome.scripting.executeScript({ target: { tabId }, files: ['conteudo.js'] });
  } catch (e) { /* aba fechou ou sem acesso */ }
}

chrome.tabs.onUpdated.addListener((tabId, info, tab) => {
  if (info.status === 'complete' && DED.test(tab.url || '')) injetar(tabId);
});

async function colocarNasAbasAbertas() {
  try {
    const abasDed = await chrome.tabs.query({ url: ['https://dedmais.educacao.mg.gov.br/*', 'https://ssc.prodemge.gov.br/*'] });
    for (const t of abasDed) await injetar(t.id);
  } catch (e) { /* sem permissão */ }
}
chrome.runtime.onInstalled.addListener(colocarNasAbasAbertas);
chrome.runtime.onStartup.addListener(colocarNasAbasAbertas);
colocarNasAbasAbertas();
