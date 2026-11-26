// =====================================================================
//  Lançador de Notas — Conector do DED+  (roda DENTRO das páginas do DED+ / Seg.ID)
//
//  O professor entra no DED+ no próprio navegador, como sempre. Este script recebe as ordens
//  do programa Lançador de Notas (pela extensão, só no próprio computador) e faz na página o
//  que o professor faria: abrir a turma, ler os alunos, digitar as notas e salvar.
//  O DED+ é um site de página única: trocar de tela não recarrega este script.
// =====================================================================
(() => {
  // uma cópia por página (a extensão coloca este arquivo quando a página abre; F5 traz a versão nova)
  if (window.__lndConector) return;
  const MINHA = 'c' + Date.now();
  window.__lndConector = MINHA;

  // ------------------------------------------------------------------ utilidades
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  async function esperar(fn, ms = 15000, passo = 250) {
    const fim = Date.now() + ms;
    while (Date.now() < fim) {
      try { const v = fn(); if (v) return v; } catch (e) { /* tenta de novo */ }
      await sleep(passo);
    }
    return null;
  }
  const visivel = e => { if (!e) return false; const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const txt = e => (e && e.textContent || '').trim();
  function clicar(el) {
    el.scrollIntoView({ block: 'center' });
    for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup']) {
      el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
    }
    el.click();
  }
  function numero(s) {
    if (s == null) return null;
    const t = String(s).trim().replace(',', '.');
    if (!t) return null;
    const n = Number(t);
    return isNaN(n) ? null : n;
  }
  const iguais = (a, b) => { const x = numero(a), y = numero(b); return x != null && y != null && Math.abs(x - y) < 0.001; };
  const fmt = v => v.toFixed(2).replace('.', ',');
  function progresso(texto) {
    try { chrome.runtime.sendMessage({ tipo: 'progresso', dados: { texto } }); } catch (e) { /* sem programa */ }
  }

  // ------------------------------------------------------------------ que tela é esta?
  const RE_DIVISAO = /^\s*\d+\s*[ºª°o]?\s*(Trimestre|Bimestre|Semestre|Per[ií]odo|Etapa)\s*$/i;

  function tela() {
    const h = location.hostname, p = location.pathname.replace(/\/+$/, '').toLowerCase();
    // innerText = só o texto VISÍVEL (textContent incluiria o código dos scripts da página)
    const corpo = document.body ? document.body.innerText : '';
    if (h.includes('ssc.prodemge')) {
      if (/sele[cç][aã]o de unidades/i.test(corpo)) return 'unidades';
      return 'login';
    }
    if (!h.includes('dedmais')) return 'outra';
    if (p.includes('avaliacao-detalhe')) return 'notas';
    // no DED+ a lista de avaliações e a tela de lançar notas têm o MESMO endereço (/avaliacao)
    if (p.includes('/avaliacao')) return /lan[cç]ar notas/i.test(corpo) || /N[aã]o realizada/i.test(corpo) ? 'notas' : 'avaliacoes';
    if (/vis[aã]o geral da turma/i.test(corpo)) return 'visao';
    if (p === '' || p === '/home' || p === '/inicio') return 'inicio';
    return 'outra';
  }
  function escola() {
    const m = (document.body.innerText || '').match(/\b\d{6}\s*-\s*[^\n]+/);
    return m ? m[0].trim() : null;
  }

  // ------------------------------------------------------------------ cartões (achados pelo texto)
  const CARTOES = {
    turma: { ancora: /\(\d{4}\)/, exige: [/MANH|TARDE|NOITE|INTEGRAL/i] },
    avaliacao: { ancora: /^\s*Valor Total/i, exige: [/Tipo/i, /Divis/i] },
    aluno: { ancora: /^\s*Nota:?\s*$/i, exige: [/N[aã]o realizada/i] }
  };
  function cartoes(tipo) {
    const { ancora, exige } = CARTOES[tipo];
    const attr = 'data-lnd-' + tipo;
    document.querySelectorAll('[' + attr + ']').forEach(e => e.removeAttribute(attr));
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const lista = [];
    let n;
    while ((n = w.nextNode())) {
      if (!ancora.test(n.textContent)) continue;
      let e = n.parentElement;
      if (!e || e.offsetParent === null) continue;
      while (e && e !== document.body) {
        const t = e.textContent || '';
        if (exige.every(x => x.test(t))) break;
        e = e.parentElement;
      }
      if (!e || e === document.body) continue;
      if (e.getBoundingClientRect().height > 450) continue;
      if (lista.some(c => c === e || c.contains(e) || e.contains(c))) continue;
      lista.push(e);
    }
    // avaliação: o título fica num bloco IRMÃO do "Valor Total/Tipo/Divisão" -> sobe até o cartão inteiro
    if (tipo === 'avaliacao') {
      for (let k = 0; k < lista.length; k++) {
        let e = lista[k];
        while (e.parentElement && e.parentElement !== document.body
               && ((e.parentElement.textContent || '').match(/Valor Total/gi) || []).length === 1) e = e.parentElement;
        lista[k] = e;
      }
    }
    lista.forEach((c, i) => c.setAttribute(attr, String(i)));
    return lista;
  }

  function rotuloTurma(texto) {
    const partes = [];
    for (const l of texto.split('\n')) {
      const s = l.trim();
      if (!s) continue;
      if (/^(rua|r\.|avenida|av\.|pra[cç]a|travessa|rodovia|estrada|alameda)\b/i.test(s)) continue;
      if (/^\d+º\s*(ano|s[eé]rie|per[ií]odo)$/i.test(s)) continue;
      partes.push(s.replace(/\s*-\s*\d+\.{0,3}$/, ''));
    }
    return partes.join(' · ');
  }

  // ------------------------------------------------------------------ navegação
  async function irInicio() {
    if (tela() === 'inicio' && cartoes('turma').length) return 'ok';
    // 1) botão "início" (casinha) no topo
    const cands = [...document.querySelectorAll('a, button, [role=button], mat-icon, i, svg, img')];
    const ehInicio = e => {
      const t = ((e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || '') + ' ' +
        (e.getAttribute('mattooltip') || '') + ' ' + (e.tagName === 'MAT-ICON' || e.tagName === 'I' ? e.textContent : '') + ' ' +
        (e.getAttribute('class') || '') + ' ' + (e.getAttribute('src') || '') + ' ' + (e.getAttribute('href') || '')).toLowerCase();
      return /\bhome\b|in[ií]cio|house/.test(t);
    };
    const casa = cands.find(e => ehInicio(e) && visivel(e) && e.getBoundingClientRect().top < 120);
    if (casa) {
      clicar(casa.closest('a, button, [role=button]') || casa);
      if (await esperar(() => tela() === 'inicio' && cartoes('turma').length, 8000)) return 'ok';
    }
    // 2) voltar do navegador
    for (let i = 0; i < 4 && tela() !== 'inicio'; i++) {
      history.back();
      await sleep(900);
    }
    if (await esperar(() => tela() === 'inicio' && cartoes('turma').length, 4000)) return 'ok';
    // 3) recarregar (o script reinicia; o programa espera a tela inicial)
    setTimeout(() => { location.href = location.origin + '/'; }, 200);
    return 'recarregando';
  }

  async function garantirAvaliacoes() {
    if (tela() === 'avaliacoes') return;
    if (tela() === 'notas') { try { await voltarParaAvaliacoes(null); } catch (e) { /* abaixo */ } }
    if (tela() !== 'avaliacoes') throw new Error('Escolha a turma de novo (o DED+ saiu da tela de avaliações).');
  }

  // ------------------------------------------------------------------ divisão (trimestre)
  function acharDivisao() {
    document.querySelectorAll('[data-lnd-divisao],[data-lnd-antes]').forEach(e => {
      e.removeAttribute('data-lnd-divisao'); e.removeAttribute('data-lnd-antes');
    });
    for (const s of document.querySelectorAll('select')) {
      const ops = [...s.options].map(o => o.text.trim());
      if (ops.some(t => RE_DIVISAO.test(t))) {
        s.setAttribute('data-lnd-divisao', 'gatilho');
        return { tipo: 'select', el: s, atual: s.options[s.selectedIndex] ? s.options[s.selectedIndex].text.trim() : null, opcoes: ops.filter(Boolean) };
      }
    }
    const folhas = []; let rot = null;
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) {
      if (!rot && /seleciona a divis/i.test(n.textContent)) rot = n.parentElement;
      const e = n.parentElement;
      if (e && RE_DIVISAO.test(e.textContent || '') && visivel(e) && !folhas.includes(e)) folhas.push(e);
    }
    folhas.forEach(e => e.setAttribute('data-lnd-antes', '1'));
    const clicavel = e => e.closest('button, select, [role=combobox], [role=button], mat-select, .mat-mdc-select, [aria-haspopup], [class*=select], [class*=dropdown]') || e;
    let alvo = null;
    if (rot) {
      const caixa = rot.closest('div, section, form') || rot.parentElement;
      alvo = folhas.find(e => (caixa && caixa.contains(e)) || (rot.parentElement && rot.parentElement.contains(e)));
    }
    if (!alvo) alvo = folhas.find(e => clicavel(e) !== e);
    if (!alvo) return { tipo: null, atual: folhas.length ? txt(folhas[0]) : null, opcoes: [] };
    return { tipo: 'custom', el: clicavel(alvo), atual: txt(alvo), opcoes: [] };
  }
  function opcoesAbertas() {
    const ops = [];
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) {
      const e = n.parentElement;
      if (e && RE_DIVISAO.test(e.textContent || '') && !e.hasAttribute('data-lnd-antes') && visivel(e) && !ops.includes(e)) ops.push(e);
    }
    return ops;
  }
  function fecharMenus() {
    for (const alvo of [document.activeElement, document.body, document]) {
      if (alvo) alvo.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', keyCode: 27, bubbles: true }));
    }
    const fundo = document.querySelector('.cdk-overlay-backdrop');
    if (fundo) fundo.click();
  }
  async function lerDivisoes() {
    const d = acharDivisao();
    let opcoes = d.opcoes.slice();
    if (d.tipo === 'custom') {
      clicar(d.el);
      await sleep(600);
      opcoes = [...new Set(opcoesAbertas().map(txt))];
      fecharMenus();
      await sleep(300);
    }
    if (d.atual) { opcoes = opcoes.filter(o => o !== d.atual); opcoes.unshift(d.atual); }
    if (!opcoes.length) opcoes = ['(divisão atual)'];
    return { atual: d.atual || opcoes[0], opcoes };
  }
  async function selecionarDivisao(nome) {
    if (!nome || nome.startsWith('(')) return;
    const d = acharDivisao();
    if (d.atual === nome) return;
    if (d.tipo === 'select') {
      const i = [...d.el.options].findIndex(o => o.text.trim() === nome);
      if (i < 0) throw new Error('Não achei a divisão "' + nome + '".');
      d.el.selectedIndex = i;
      d.el.dispatchEvent(new Event('change', { bubbles: true }));
    } else if (d.tipo === 'custom') {
      clicar(d.el);
      await sleep(600);
      const op = opcoesAbertas().find(e => txt(e) === nome);
      if (!op) { fecharMenus(); throw new Error('Não achei a divisão "' + nome + '".'); }
      clicar(op.closest('[role=option], mat-option, li') || op);
    }
    await sleep(800);
    await esperar(() => cartoes('avaliacao').length || /nenhuma avalia/i.test(document.body.innerText), 8000);
  }

  // ------------------------------------------------------------------ leituras
  /** Parece nome de pessoa? (2+ palavras, só letras; aceita minúsculas — o estilo pode pôr em maiúsculas) */
  const pareceNome = t => /^[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ'. -]{4,}$/.test(t) && t.trim().split(/\s+/).length >= 2
                          && !/total|alunos|turma|notas|avalia|relat|frequ|google|sala de aula/i.test(t);

  function alunosVisaoGeral() {
    const tot = (document.body.innerText.match(/Total de alunos:?\s*(\d+)/i) || [])[1];
    const alunos = alunosPorMatricula();
    const total = tot ? Number(tot) : null;
    return (alunos.length && (!total || alunos.length === total)) ? alunos : [];
  }

  /** Acha alunos em qualquer tela: cada número de matrícula + o nome que está junto dele. */
  function alunosPorMatricula() {
    const alunos = []; const vistos = new Set();
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) {
      const mat = n.textContent.trim();
      if (!/^\d{6,10}$/.test(mat) || vistos.has(mat)) continue;
      // sobe até o bloco do aluno: o menor bloco com UM texto que parece nome
      let e = n.parentElement, nome = null;
      for (let i = 0; i < 5 && e && e !== document.body; i++, e = e.parentElement) {
        const nomes = [...e.querySelectorAll('*')].filter(x => ![...x.children].some(c => (c.textContent || '').trim())
                        && pareceNome(txt(x)));
        const unicos = [...new Set(nomes.map(x => txt(x)))];
        if (unicos.length === 1) { nome = nomes[0]; break; }
        if (unicos.length > 1) break;
      }
      if (!nome) continue;
      vistos.add(mat);
      alunos.push({ matricula: mat, nome: (nome.innerText || txt(nome)).trim().replace(/\s+/g, ' ').toUpperCase() });
    }
    return alunos;
  }

  /**
   * Título da avaliação: no DED+ é um texto sublinhado/clicável no topo do cartão
   * (no código vem "Produção do trabalho"; a tela mostra em MAIÚSCULAS pelo estilo).
   */
  const RE_NAO_TITULO = /copiar|excluir|editar|nota (pendente|registrada)|valor total|tipo:|divis/i;
  function tituloDoCartao(card) {
    const folhas = [...card.querySelectorAll('*')].filter(e =>
      ![...e.children].some(c => (c.textContent || '').trim()) && txt(e).length >= 3
      && !RE_NAO_TITULO.test(txt(e)) && !/^(MAT-ICON|I|SVG|PATH)$/i.test(e.tagName) && !e.closest('.v-chip, mat-chip, [class*=chip]'));
    const clicavel = folhas.find(e => {
      for (let p = e; p && p !== card; p = p.parentElement) {
        const st = getComputedStyle(p);
        if (st.cursor === 'pointer' || /underline/.test(st.textDecorationLine || '') || p.tagName === 'A'
            || /cursor-pointer|text-decoration-underline/.test(p.className || '')) return true;
      }
      return false;
    });
    return clicavel || folhas[0] || null;
  }

  function avaliacoes() {
    return cartoes('avaliacao').map((c, i) => {
      const texto = c.innerText.trim();
      const t = tituloDoCartao(c);
      const nome = t ? (t.innerText || txt(t)) : texto.split('\n')[0];
      const m = texto.match(/Valor Total:?\s*([\d.,]+)/);
      return { indice: i, nome: nome.replace(/\s+/g, ' ').trim(), valor: m ? numero(m[1]) : null };
    });
  }

  function lerAlunosNaTela() {
    const reNota = CARTOES.aluno.ancora;
    document.querySelectorAll('[data-lnd-nota]').forEach(e => e.removeAttribute('data-lnd-nota'));
    return cartoes('aluno').map((c, i) => {
      const t = c.innerText;
      const mat = (t.match(/\b\d{6,10}\b/) || [''])[0];
      const link = c.querySelector('a');
      const linhas = t.split('\n').map(s => s.trim()).filter(Boolean);
      const nome = (link && pareceNome(txt(link)) ? link.innerText : (linhas.find(l => pareceNome(l)) || '')).trim().toUpperCase();
      const campos = [...c.querySelectorAll('input')].filter(x => !['hidden', 'radio', 'checkbox'].includes(x.type));
      let rotulo = null;
      const w = document.createTreeWalker(c, NodeFilter.SHOW_TEXT);
      let n; while ((n = w.nextNode())) { if (reNota.test(n.textContent)) { rotulo = n; break; } }
      let campo = c.querySelector('input.nota') || campos[0];
      if (rotulo && !c.querySelector('input.nota')) {
        const depois = campos.find(x => rotulo.compareDocumentPosition(x) & Node.DOCUMENT_POSITION_FOLLOWING);
        if (depois) campo = depois;
      }
      const marcadores = [...c.querySelectorAll('input[type=radio], input[type=checkbox], [role=radio], [role=checkbox]')];
      const nr = marcadores.some(r => r.checked || r.getAttribute('aria-checked') === 'true');
      return { card: c, campo, matricula: mat, nome, valor: campo ? campo.value : '', nr };
    });
  }

  // ------------------------------------------------------------------ abrir turma / avaliação
  async function entrarEmAvaliacoes() {
    const exato = e => /^\s*avalia[cç][oõ]es\s*$/i.test(e.textContent || '');
    let folha = null;
    const w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) { const e = n.parentElement; if (e && exato(e) && visivel(e)) { folha = e; break; } }
    if (!folha) throw new Error('Não achei o atalho "Avaliações" na Visão Geral da Turma.');
    const alvos = [];
    const add = e => { if (e && e !== document.body && !alvos.includes(e)) alvos.push(e); };
    add(folha.closest('a, button, [role=button], [role=link], [routerlink]'));
    let p = folha;
    for (let i = 0; i < 4 && p.parentElement; i++) {
      p = p.parentElement;
      if (/avalia[cç][oõ]es\s+simave/i.test(p.textContent || '') || /chamada/i.test(p.textContent || '')) break;
      if (getComputedStyle(p).cursor === 'pointer') { add(p); break; }
    }
    const bloco = folha.parentElement;
    if (bloco) add([...bloco.querySelectorAll('img, svg, mat-icon, i, button, a, div')].find(e => e !== folha && !e.contains(folha) && visivel(e)));
    add(bloco); add(folha);
    for (const a of alvos) {
      clicar(a);
      if (await esperar(() => tela() === 'avaliacoes', 6000)) return;
    }
    throw new Error('Não consegui entrar em Avaliações desta turma.');
  }

  async function abrirAvaliacao(nome) {
    await esperar(() => cartoes('avaliacao').length, 8000);
    const lista = avaliacoes();
    const av = lista.find(a => a.nome === nome) || lista.find(a => a.nome.toUpperCase() === String(nome).toUpperCase());
    if (!av) throw new Error('Não achei a avaliação "' + nome + '" no DED+.');
    const card = document.querySelector('[data-lnd-avaliacao="' + av.indice + '"]');
    const proibido = e => /copiar|excluir|editar|valor total|tipo:|divis/i.test(e.textContent || '');
    const titulo = tituloDoCartao(card);
    const alvos = [];
    const add = e => { if (e && card.contains(e) && !alvos.includes(e)) alvos.push(e); };
    if (titulo) {
      add(titulo);
      add(titulo.closest('a, button, [role=button], [role=link], [routerlink]'));
      let p = titulo;
      for (let k = 0; k < 3 && p.parentElement && p.parentElement !== card; k++) {
        p = p.parentElement;
        if (getComputedStyle(p).cursor === 'pointer' && !proibido(p)) { add(p); break; }
      }
    }
    [...card.querySelectorAll('a')].filter(a => !proibido(a) && txt(a).length > 2).forEach(add);
    add(card);
    const antes = location.href;
    for (const a of alvos) {
      clicar(a);
      const abriu = await esperar(() => cartoes('aluno').length || (location.href !== antes && alunosPorMatricula().length), 7000);
      if (abriu) { await sleep(600); return; }
    }
    throw new Error('Não consegui abrir a tela de lançar notas de "' + nome + '".');
  }

  async function voltarParaAvaliacoes(divisao) {
    if (tela() === 'notas') {
      const bt = [...document.querySelectorAll('button, a, [role=button]')]
        .find(e => /^\s*voltar\s*$/i.test(e.innerText || '') && visivel(e));
      if (bt) clicar(bt); else history.back();
      if (!await esperar(() => tela() === 'avaliacoes', 8000)) { history.back(); await esperar(() => tela() === 'avaliacoes', 8000); }
    }
    if (tela() !== 'avaliacoes') throw new Error('Não consegui voltar para Avaliações.');
    if (divisao) await selecionarDivisao(divisao);
    await esperar(() => cartoes('avaliacao').length, 8000);
  }

  // ------------------------------------------------------------------ digitar nota
  const setValor = (el, v) => {
    const set = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  async function digitar(campo, valorFmt) {
    if (campo.type === 'number') {
      const v = String(numero(valorFmt));               // 8 ; 7.5  (campo numérico não aceita vírgula)
      campo.focus();
      setValor(campo, '');
      setValor(campo, v);
      campo.dispatchEvent(new Event('change', { bubbles: true }));
      for (const t of ['keydown', 'keypress', 'keyup']) {
        campo.dispatchEvent(new KeyboardEvent(t, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true }));
      }
      if (campo.form) campo.form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      campo.dispatchEvent(new Event('blur', { bubbles: true }));
      campo.blur();
      await sleep(80);
      return iguais(campo.value, v) ? null : 'o campo não aceitou ' + valorFmt + ' (ficou "' + campo.value + '")';
    }
    campo.focus();
    setValor(campo, '');
    setValor(campo, valorFmt);
    campo.dispatchEvent(new Event('change', { bubbles: true }));
    campo.dispatchEvent(new Event('blur', { bubbles: true }));
    campo.blur();
    await sleep(60);
    if (iguais(campo.value, valorFmt)) return null;
    // campo com máscara: "digita" algarismo por algarismo ("8,00" -> 8, 80, 800)
    const dig = valorFmt.replace(/\D/g, '');
    campo.focus();
    setValor(campo, '');
    let acc = '';
    for (const d of dig) { acc += d; setValor(campo, acc); await sleep(25); }
    campo.dispatchEvent(new Event('change', { bubbles: true }));
    campo.blur();
    await sleep(60);
    return iguais(campo.value, valorFmt) ? null : 'o campo não aceitou ' + valorFmt + ' (ficou "' + campo.value + '")';
  }
  function marcarNaoRealizada(card) {
    const marcadores = [...card.querySelectorAll('input[type=radio], input[type=checkbox], [role=radio], [role=checkbox], mat-radio-button, mat-checkbox')];
    let alvo = marcadores.find(a => /n[aã]o realizada/i.test(((a.closest('label') || a.parentElement || a).textContent) || ''));
    if (!alvo) alvo = marcadores[marcadores.length - 1];
    if (!alvo) return false;
    if (alvo.checked || alvo.getAttribute('aria-checked') === 'true') return true;
    clicar(alvo);
    return true;
  }

  // ------------------------------------------------------------------ ORDENS DO PROGRAMA
  const ORDENS = {
    async estado() {
      return { tela: tela(), url: location.href, escola: tela() === 'login' ? null : escola() };
    },

    async listarEscolas() {
      const out = []; let i = 0;
      for (const tr of document.querySelectorAll('tr')) {
        const tds = [...tr.querySelectorAll('td')].map(td => td.innerText.trim()).filter(Boolean);
        const cod = tds.find(t => /^\d{5,8}$/.test(t));
        if (!cod) continue;
        tr.setAttribute('data-lnd-escola', String(i));
        out.push({ indice: i, codigo: cod, nome: tds[tds.length - 1] });
        i++;
      }
      return out;
    },

    async escolherEscola({ indice }) {
      await ORDENS.listarEscolas();
      const tr = document.querySelector('[data-lnd-escola="' + indice + '"]');
      if (!tr) throw new Error('Escola não encontrada na lista.');
      setTimeout(() => clicar(tr), 200); // a página vai mudar: responde antes
      return true;
    },

    async trocarUnidade() {
      const el = [...document.querySelectorAll('button, a, [role=button], span, div')]
        .find(e => /^\s*trocar unidade\s*$/i.test(e.textContent || '') && visivel(e));
      if (!el) throw new Error('Não achei o botão "Trocar Unidade".');
      setTimeout(() => clicar(el.closest('button, a, [role=button]') || el), 200);
      return true;
    },

    async irInicio() { return irInicio(); },

    async listarTurmas() {
      const r = await irInicio();
      if (r === 'recarregando') return { recarregando: true };
      await esperar(() => cartoes('turma').length, 10000);
      return { turmas: cartoes('turma').map(c => rotuloTurma(c.innerText.trim())) };
    },

    async abrirTurma({ rotulo }) {
      const r = await irInicio();
      if (r === 'recarregando') return { recarregando: true };
      await esperar(() => cartoes('turma').length, 10000);
      const card = cartoes('turma').find(c => rotuloTurma(c.innerText.trim()) === rotulo);
      if (!card) throw new Error('Não achei a turma "' + rotulo + '" na tela inicial do DED+.');
      progresso('Abrindo a turma...');
      clicar(card);
      const t = await esperar(() => ['visao', 'avaliacoes'].includes(tela()) ? tela() : null, 15000);
      if (!t) throw new Error('A turma não abriu.');
      let alunos = [];
      let htmlVisao = null;
      if (t === 'visao') {
        await esperar(() => /Total de alunos/i.test(document.body.innerText), 5000);
        alunos = alunosVisaoGeral();
        if (!alunos.length) htmlVisao = document.documentElement.outerHTML;
        progresso('Entrando em Avaliações...');
        await entrarEmAvaliacoes();
      }
      await esperar(() => acharDivisao().atual || cartoes('avaliacao').length, 8000);
      const div = await lerDivisoes();
      return { alunos, divisoes: div.opcoes, htmlVisao };
    },

    async listarAvaliacoes({ divisao }) {
      await garantirAvaliacoes();
      await selecionarDivisao(divisao);
      return avaliacoes().map(a => ({ indice: a.indice, nome: a.nome, valor: a.valor }));
    },

    async lerAlunosNotas({ divisao }) {
      await garantirAvaliacoes();
      await selecionarDivisao(divisao);
      const lista = avaliacoes();
      if (!lista.length) return [];
      await abrirAvaliacao(lista[0].nome);
      let alunos = lerAlunosNaTela().map(a => ({ matricula: a.matricula, nome: a.nome })).filter(a => a.nome);
      if (!alunos.length) alunos = alunosPorMatricula();
      const html = document.documentElement.outerHTML; // diagnóstico (o programa guarda em capturas/)
      await voltarParaAvaliacoes(divisao);
      return { alunos, html };
    },

    /** notas: { matrícula: "8,5" | "NR" } */
    async lancar({ divisao, avaliacao, notas }) {
      await garantirAvaliacoes();
      await selecionarDivisao(divisao);
      progresso('Abrindo "' + avaliacao + '"...');
      await abrirAvaliacao(avaliacao);

      const res = {};
      const porMat = {};
      for (const a of lerAlunosNaTela()) porMat[a.matricula] = a;
      const alterados = {};
      const total = Object.keys(notas).length; let feitos = 0;

      for (const [mat, nota] of Object.entries(notas)) {
        feitos++;
        const a = porMat[mat];
        if (!a) { res[mat] = { ok: false, mensagem: 'aluno não está na tela desta avaliação' }; continue; }
        if (String(nota).trim().toUpperCase() === 'NR') {
          if (a.nr) { res[mat] = { ok: true, mensagem: 'já estava "não realizada"' }; continue; }
          if (!marcarNaoRealizada(a.card)) { res[mat] = { ok: false, mensagem: 'não achei a opção "Não realizada"' }; continue; }
          alterados[mat] = 'NR';
        } else {
          const v = numero(nota);
          if (v == null) { res[mat] = { ok: false, mensagem: 'nota inválida: ' + nota }; continue; }
          const f = fmt(v);
          if (iguais(a.valor, f)) { res[mat] = { ok: true, mensagem: 'já estava ' + f }; continue; }
          if (!a.campo) { res[mat] = { ok: false, mensagem: 'campo de nota não encontrado' }; continue; }
          const erro = await digitar(a.campo, f);
          if (erro) { res[mat] = { ok: false, mensagem: erro }; continue; }
          alterados[mat] = f;
        }
        if (feitos % 5 === 0) progresso('Digitando notas: ' + feitos + ' de ' + total);
        await sleep(60);
      }

      if (!Object.keys(alterados).length) {
        await voltarParaAvaliacoes(divisao);
        return res;
      }

      progresso('Salvando no DED+...');
      const naoSalvo = () => /ainda n[aã]o foram salvas/i.test(document.body.innerText);
      const botaoSalvar = () => [...document.querySelectorAll('button, [role=button], a')]
        .find(e => /\b(salvar|gravar|registrar)\b/i.test(e.innerText || '') && visivel(e) && !e.disabled);
      // o DED+ salva sozinho ou mostra um botão Salvar quando tudo está válido
      const sinal = await esperar(() => botaoSalvar() || (!naoSalvo() && 'ok'), 6000);
      if (sinal && sinal !== 'ok') { clicar(sinal); await sleep(800); }
      await esperar(() => !naoSalvo(), 15000);
      const erroCampo = [...document.querySelectorAll('.text-errorText, .text-error')].map(e => txt(e)).find(Boolean);
      if (naoSalvo()) throw new Error('O DED+ não salvou as notas' + (erroCampo ? ': ' + erroCampo : '.') + ' Confira a tela do DED+.');
      await sleep(800);

      // Conferência: sai e entra de novo na avaliação (o DED+ busca do servidor) e compara.
      progresso('Conferindo o que o DED+ gravou...');
      const antes = {};
      for (const m of Object.keys(alterados)) antes[m] = porMat[m] ? porMat[m].valor : '';
      await voltarParaAvaliacoes(divisao);
      await abrirAvaliacao(avaliacao);
      const depois = {};
      for (const a of lerAlunosNaTela()) depois[a.matricula] = a;
      for (const [mat, enviado] of Object.entries(alterados)) {
        const d = depois[mat];
        if (!d) { res[mat] = { ok: false, mensagem: 'não consegui conferir após salvar' }; continue; }
        const ok = enviado === 'NR' ? d.nr : iguais(d.valor, enviado);
        res[mat] = ok
          ? { ok: true, mensagem: (antes[mat] && antes[mat] !== '-' ? 'alterado de ' + antes[mat] : 'lançado') }
          : { ok: false, mensagem: 'DED+ mostra "' + d.valor + '" após salvar' };
      }
      await voltarParaAvaliacoes(divisao);
      return res;
    },

    /** Código da página (sem senhas), para diagnóstico. */
    async pagina() {
      document.querySelectorAll('input[type=password]').forEach(i => { i.value = ''; });
      return { url: location.href, html: document.documentElement.outerHTML };
    }
  };

  // ------------------------------------------------------------------ laço: busca ordens no programa
  window.__lndTeste = { ORDENS, tela, cartoes, irInicio, avaliacoes, tituloDoCartao, alunosVisaoGeral, lerAlunosNaTela, digitar }; // usado só nos testes automáticos

  let ocupado = false;
  async function ciclo() {
    // Na tela de LOGIN do Seg.ID o conector fica totalmente quieto (não conversa com o programa).
    // Só começa a trabalhar na "Seleção de Unidades" ou dentro do DED+.
    while (location.hostname.includes('ssc.prodemge') && tela() === 'login') await sleep(1500);
    while (window.__lndConector === MINHA) {
      try {
        if (!ocupado) {
          const cmd = await chrome.runtime.sendMessage({
            tipo: 'buscar', url: location.href, visivel: document.visibilityState === 'visible'
          });
          if (cmd && cmd.id && ORDENS[cmd.cmd]) {
            ocupado = true;
            let dados;
            try { dados = { id: cmd.id, ok: true, resultado: await ORDENS[cmd.cmd](cmd.args || {}) }; }
            catch (e) { dados = { id: cmd.id, ok: false, erro: String((e && e.message) || e) }; }
            ocupado = false;
            await chrome.runtime.sendMessage({ tipo: 'resposta', dados });
            continue;
          }
        }
      } catch (e) {
        if (String(e).includes('Extension context invalidated')) return; // extensão foi atualizada
      }
      await sleep(700);
    }
  }
  ciclo();
})();
