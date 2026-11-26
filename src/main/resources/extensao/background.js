// Lançador de Notas — ponte entre as páginas do DED+ e o programa (só no próprio computador).
// As ordens do programa chegam por http://127.0.0.1:47321 e vão para UMA aba do DED+ (a "principal").
importScripts('token.js'); // define TOKEN (gerado pelo programa; impede que sites falem com o programa)

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
    fetch(PROGRAMA + '/proximo' + q, { cache: 'no-store', headers: { 'X-Token': TOKEN } })
      .then(r => (r.status === 200 ? r.json() : null))
      .then(cmd => {
        if (cmd && cmd.cmd === '__recarregar_extensao') { responder(null); chrome.runtime.reload(); return; }
        responder(cmd);
      })
      .catch(() => responder(null));
    return true; // resposta assíncrona
  }
  if (msg.tipo === 'resposta' || msg.tipo === 'progresso') {
    fetch(PROGRAMA + '/' + msg.tipo, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Token': TOKEN },
      body: JSON.stringify(msg.dados)
    }).then(() => responder(true)).catch(() => responder(false));
    return true;
  }
});
