# Lançador de Notas — DED+ MG

Programa desktop (JavaFX) que lê a foto da folha de notas e lança tudo no DED+.

## Como o professor usa

1. **Login:** o professor clica em "Abrir o DED+ para entrar" e entra no DED+ numa janela **normal** do navegador, como sempre faz (CPF, senha e "Confirme que é humano"). O programa só se conecta depois que o login terminou. A janela do navegador fica aberta atrás do programa: não feche nem minimize.
2. **Escolhe a escola.** A lista vem da tela "Seleção de Unidades". A última escola usada já vem marcada e dá para entrar direto nela. Na tela principal há o botão **Trocar escola**.
3. Escolhe a **turma** e a **divisão** (1º/2º/3º trimestre). O programa traz do DED+ as avaliações e os alunos.
4. **Folha para impressão** (opcional, recomendado): gera a folha com os nomes da turma na ordem do DED+ e uma coluna por avaliação. O professor só escreve as notas.
5. **Escolher foto → Ler folha.** A IA lê as colunas (Atv 1, Atv 2, …) e as notas.
6. **Cada coluna → avaliação.** Por padrão, *Atv 1 → 1ª avaliação* ("Produção do trabalho"), *Atv 2 → 2ª* ("Apresentação do trabalho") e assim por diante. Colunas sem nota, ou como Recup./Avalia, ficam "não lançar" até o professor escolher.
7. **Conferir.** Linhas amarelas precisam de atenção. Dois cliques corrigem nome ou nota; o botão direito marca a linha como conferida ou manda não lançar.
8. **Lançar no DED+.** Para cada avaliação, o programa abre a tela, preenche, salva, **recarrega a página e confere aluno por aluno** o que ficou gravado.

## Folha para impressão

- Os nomes já vêm impressos na ordem do DED+. O Nº da linha diz quem é o aluno, e o nome impresso serve de conferência.
- O código no topo (ex.: `LND-082716-470D04`) identifica escola, turma e divisão. Se a foto for de outra turma, o programa avisa.
- Para notas: algarismos com vírgula (`7,5`). Em branco = não lançar. `NR` = não realizada.
- Abre no navegador: **Imprimir** ou **Salvar como PDF**. Também existe uma versão **em branco** para turmas avulsas.
- Turmas com até cerca de 34 alunos cabem numa folha A4. Com mais alunos, a folha continua na página 2, com o cabeçalho repetido.

## Instalador para os professores (Windows)

O professor recebe **um único arquivo**, `Instalar-Lancador-de-Notas.exe`, e mais nada:

- leva o Java junto: não precisa instalar nada antes;
- instala só para o usuário: **não pede senha de administrador** (funciona nos computadores da escola);
- assistente em português com 2 cliques (Instalar → Concluir) e ícone na Área de Trabalho;
- se você colocar a chave da API ao gerar o instalador, o professor não configura nada;
- depois de instalado, ele se **atualiza sozinho** pela internet (veja "Atualizações automáticas").

### Como gerar o instalador (feito por você, uma vez por versão)

1. Descompacte o projeto num computador com **Windows 10 ou 11** e internet.
2. Abra a pasta `instalador` e dê dois cliques em **`CRIAR-INSTALADOR.bat`**.
3. Na primeira vez ele baixa sozinho o Java 21, o Maven e o Inno Setup para a pasta `ferramentas` (uns 300 MB, de 5 a 15 minutos).
4. Quando ele pedir, **cole a chave da API** da Anthropic, ou aperte Enter para pular.
5. No fim, a pasta abre sozinha com o `Instalar-Lancador-de-Notas.exe`.

Envie para os professores o `.exe` junto com o **`instalador/Guia-de-instalacao-para-professores.pdf`** (2 páginas, letra grande: instalar e usar).

O instalador só precisa ser gerado de novo quando as bibliotecas mudarem; o resto chega pela atualização automática (veja abaixo).

### Avisos importantes

- **Tela azul "O Windows protegeu o computador" (SmartScreen):** aparece porque o instalador não tem assinatura digital. O guia ensina a passar por ela ("Mais informações" → "Executar assim mesmo"). Para eliminar o aviso é preciso um certificado de assinatura de código, que é pago.
- **Chave da API dentro do instalador:** quem tiver o instalador consegue extrair a chave, e todo uso é cobrado na sua conta. No console da Anthropic, **defina um limite de gasto mensal** e use uma chave só para este programa, que você pode trocar quando quiser.

## Atualizações automáticas

Depois de instalado, **o programa se atualiza sozinho**. Não precisa reinstalar a cada mudança.

```
alteração no código (src/ ou pom.xml) → push no GitHub (branch main)
   → GitHub Actions compila e testa num Windows → publica a Release "v1.1.N"
     (lancador-notas-ded.jar, cerca de 100 KB, + versao.json com a assinatura SHA-256)
   → o programa do professor, ao abrir, vê a versão nova, baixa, confere e pergunta
     "Atualizar agora?" → fecha, troca o arquivo e abre de novo (poucos segundos)
```

- Repositório: **https://github.com/royalparafusos/lancador-notas** (público: é o que permite baixar sem senha). A chave do Groq **não** está no repositório; ela vai só dentro do instalador.
- A versão aparece no título da janela.
- Sem internet, o programa funciona normalmente e só não procura atualização.
- O programa fica dividido em dois arquivos:
  - `lancador-notas-ded.jar`: o nosso código. **É o único que a atualização troca.**
  - `lancador-libs.jar`: JavaFX, Playwright e Jackson. Vai só no instalador.
- **Mudou alguma biblioteca?** Aumente `libs.versao` no `pom.xml`. Os programas instalados avisam "precisa instalar de novo", e aí é preciso gerar um instalador novo com o `CRIAR-INSTALADOR.bat`.
- A versão anterior fica guardada como `lancador-notas-ded.jar.anterior` na pasta do programa, caso seja preciso voltar.

## Leitura das fotos: Groq (gratuito) ou Claude (pago)

O programa escolhe sozinho:

| Motor | Custo | Como conseguir a chave | Limites |
|---|---|---|---|
| **Groq** (modelo `qwen/qwen3.8-27b`) | **gratuito** | console.groq.com → API Keys (começa com `gsk_`) | por **conta Groq**, somando todos que usam a mesma chave: ~1 foto por minuto e ~35 fotos por dia |
| Claude (Anthropic) | alguns centavos por foto | console.anthropic.com (começa com `sk-ant-`) | praticamente sem limite |

Se houver chave do Groq, usa o Groq. Se não houver, usa a do Claude.

**Atenção ao limite gratuito:** se a escola inteira usar a mesma chave do Groq, as ~35 fotos por dia são divididas entre todos. Para mais professores: cada escola cria a própria chave, ou use o Claude. Quando o limite por minuto estoura, o programa espera e tenta de novo sozinho. Quando estoura o limite do dia, ele avisa que o limite volta no dia seguinte.

## Requisitos e execução (para desenvolver)

- **Java 21** (ex.: Temurin: https://adoptium.net) e **Maven 3.9+**
- Microsoft Edge ou Google Chrome instalado (o programa usa o navegador que já existe)
- Chave do **Groq** (gratuita) ou da Anthropic para ler as fotos, colada em *Configurações*

```bash
mvn javafx:run                 # roda direto
mvn package                    # gera target/lancador-notas-ded.jar  (gere no próprio Windows)
java -jar target/lancador-notas-ded.jar
```

## Por que o login é feito pelo próprio professor

O Seg.ID tem a verificação **Cloudflare Turnstile** ("Confirme que é humano"), que bloqueia navegadores controlados por programa. No começo o programa preenchia o login sozinho, e o Chrome mostrava "está sendo controlado por um software de teste automatizado"; a verificação falhava.

Agora o programa abre o **navegador padrão do computador** como um navegador normal. Ele aceita Chrome, Edge, Brave e Vivaldi; se o padrão for o Firefox, que não aceita a ligação com o programa, ele usa o Edge. O navegador abre, sem nenhum controle e num perfil próprio (`~/.lancador-notas-ded/perfil-navegador`). O professor faz o login ele mesmo, e o programa não digita, não vê e não guarda a senha. Ele só acompanha o endereço da aba. Quando o login termina (aparece a lista de escolas ou as turmas), ele se conecta ao navegador, que fica só no próprio computador, e segue o trabalho. A janela do navegador **nunca é minimizada**: minimizada, ela para de desenhar a página e os cliques travam. Ela pode ficar atrás do programa. Também há o botão "Já entrei, continuar", caso a detecção automática não perceba.

O programa **não disfarça nem burla** a verificação: quem passa por ela é a pessoa.

## Como o programa encontra as coisas no DED+

As telas de turmas, avaliações e notas são reconhecidas **pelos textos** ("(2026)" + turno, "Valor Total" + "Tipo", "Nota:" + "Não realizada"), não por códigos internos da página. Assim, uma mudança visual no DED+ dificilmente quebra o programa. Esses textos e os seletores do login ficam em:

`~/.lancador-notas-ded/ded-seletores.properties` (dá para editar sem recompilar)

Se algo falhar, uma captura da tela fica em `~/.lancador-notas-ded/capturas/`. O botão **Calibrar DED** mostra o navegador com o inspetor do Playwright.

## O que foi testado e o que falta testar

| Parte | Situação |
|---|---|
| Compilação de todo o código | ✅ com JavaFX e Jackson reais e o Playwright em "molde" (o ambiente de desenvolvimento não tinha acesso ao Maven Central) |
| Reconhecimento de escolas, turmas, avaliações e alunos | ✅ testado no Chrome com páginas que imitam as telas reais (capturas de 25/11/2026) |
| Casamento de nomes | ✅ 8 testes automáticos; com os nomes da folha real: 27 de 34 automáticos e corretos, 7 para conferência, **0 errados** |
| Tela de conferência | ✅ testada com dados fictícios |
| Login pelo professor + conexão depois | ✅ testado com Chrome real: sem a faixa "controlado por software", `navigator.webdriver=false` durante o login, conexão posterior ok |
| Tela de unidades real | ⚠️ falta o primeiro uso de verdade |
| Salvar no DED+ real | ⚠️ **ainda não testado.** No primeiro uso, lance uma avaliação de teste e confira no DED+ |

## Limitações conhecidas

- Não cadastra avaliações novas. Elas precisam existir no DED+ antes ("Cadastrar avaliação").
- Não lança Recuperação Paralela.
- Turmas com dois alunos de mesmo primeiro nome: escreva a inicial do sobrenome no caderno ("Lucas C.") ou use a folha do programa.

## Privacidade

- A senha **nunca é gravada**.
- **LGPD:** a foto, com nomes e notas de alunos, é enviada ao serviço de leitura (Groq ou Anthropic). A escola deve estar ciente.
- O lançamento é responsabilidade do professor. Confira sempre a tabela antes de lançar.
