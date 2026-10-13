# apimgr-policy-debugger (apd)

Sandbox de linha de comando para **executar, depurar e testar policies do Axway API Gateway 7.7** sem subir
uma instância do gateway.

As policies rodam no **motor real da Axway** (`InvocationEngine`, filtros, seletores, JRE e bibliotecas JNI do
produto), carregado dentro do `vshell` com um bootstrap próprio — sem NetService, listeners ou Node Manager.
O apd substitui apenas o que pertence ao processo do gateway: a entrada HTTP vira uma `Message` montada com os
mesmos atributos do listener, e o roteamento para backends é atendido por mocks.

```
$ apimgr-policy-debugger run --config QuickStart.fed --url /heroes --mock heroes-mock.yaml

▸ Parse REST Operation  pass  21.6 ms
  • Set Service context                      [ServiceContextFilter]       true  0.17 ms
  • Parse REST Operation                     [SwitchFilter]               true  25.32 ms
    • Id-0001347371498253-ffffffff851382a0-1 [CircuitDelegateFilter]      true  21.61 ms
      ▸ GetProducts  pass  21.6 ms
        • Connect to Heroes' REST Service    [ConnectToURLFilter]         true  7.79 ms
            + filter.connect.to.url.result = SUCCESS
            + http.response.status = 200
            ~ http.request.uri = http://heroes.org/productService/REST/GetProducts
        • Reflect Message                    [Reflector]                  true  13.65 ms

PASS  path Default Services /heroes  status=200  116.9 ms
  backend: GET heroes.org/productService/REST/GetProducts -> 200
  Content-Type: [application/json]

[{"id": 1, "name": "Cape", "price": 10.5}, {"id": 2, "name": "Mask", "price": 4.0}]
```

## Sumário

- [Recursos](#recursos)
- [Requisitos](#requisitos)
- [Instalação](#instalação)
- [Primeiros passos](#primeiros-passos)
- [Configuração de entrada](#configuração-de-entrada)
- [Comandos](#comandos)
- [Executando requests](#executando-requests)
- [Ciclo de vida emulado](#ciclo-de-vida-emulado)
- [Backend virtual e mocks](#backend-virtual-e-mocks)
- [Testes automatizados](#testes-automatizados)
- [Debugger interativo](#debugger-interativo)
- [Modo shell](#modo-shell)
- [Sandbox e limites](#sandbox-e-limites)
- [Desempenho](#desempenho)
- [Desenvolvimento](#desenvolvimento)
- [Documentação técnica](#documentação-técnica)

## Recursos

- Carrega `.fed`, `.pol` + `.env`, configuração implantada de grupo (`configs.xml`) ou store YAML, aplicando
  environment settings e `envSettings.props` como o gateway.
- Executa uma policy isolada, o ciclo completo de um path de listener ou uma cadeia montada à mão.
- Fault handlers da policy, Global Fault Handler e last-chance handler com a semântica real.
- **Trace em árvore** com resultado, tempo e **diff de atributos por filtro**.
- **Debugger**: breakpoints, step, inspeção/edição de atributos, avaliação de seletores `${…}`, resultado forçado.
- **Backend virtual** para filtros de roteamento, com falhas simuladas (timeout, conexão recusada, latência).
- **Testes em YAML** com asserções de status, headers, body, atributos, filtros executados, backend chamado;
  saída JUnit para CI.
- **Isolamento de rede**: nada sai do processo sem permissão explícita.
- Modo `shell`: boot uma vez, dezenas de execuções, `reload` em < 1 s.

## Requisitos

| Item | Detalhe |
|---|---|
| Sistema | **Linux x86_64** (o runtime Axway 7.7 só existe para Linux). WSL2 funciona como um Linux comum. Testado em Ubuntu 20.04 |
| Axway | uma instalação do API Gateway 7.7 (pasta `apigateway` ou `.tar.gz`) para o setup copiar o runtime |
| Build | JDK ≥ 11 (ex.: `openjdk-17-jdk-headless`) — só para compilar a partir do código |
| Recursos | ~650 MB de disco para o runtime; ~0,5 GB de RAM por execução |

## Instalação

**A partir do pacote** (`apimgr-policy-debugger-<versão>.tar.gz`, ~100 KB, sem nada da Axway dentro):
```sh
tar -xzf apimgr-policy-debugger-1.0.0.tar.gz -C ~/tools
sudo sh ~/tools/apimgr-policy-debugger-1.0.0/bin/setup.sh /caminho/Axway/apigateway   # ou o .tar.gz da Axway
export PATH="$HOME/tools/apimgr-policy-debugger-1.0.0/bin:$PATH"
apimgr-policy-debugger --version
```

**A partir do repositório:**
```sh
git clone https://github.com/JoaoAngelo/apimgr-policy-debugger.git && cd apimgr-policy-debugger
sudo sh bin/setup.sh /caminho/opt-Axway.tar.gz
sh build.sh
bin/apimgr-policy-debugger --version
```

O `setup.sh` copia da instalação Axway só `Linux.x86_64`, `platform`, `system`, `posix` e `conf` para
`/opt/axway/apigateway` (2º argumento muda o destino) e grava o local em `conf/runtime.env`.
`AXWAY_HOME` sobrescreve.

**Docker** (CI; imagem construída localmente com o runtime de quem usa):
```sh
sh docker/build.sh /caminho/opt-Axway.tar.gz            # imagem apimgr-policy-debugger:1.0.0
docker run --rm -v "$PWD:/work" apimgr-policy-debugger:1.0.0 test tests/ --config MinhaConfig.fed --junit junit.xml
```

## Primeiros passos

```sh
CFG=~/deploy/MinhaConfig.fed

apimgr-policy-debugger doctor   --config $CFG          # runtime, módulos, isolamento
apimgr-policy-debugger paths    --config $CFG          # paths de listener → policy
apimgr-policy-debugger policies --config $CFG oauth    # policies cujo caminho contém "oauth"
apimgr-policy-debugger run      --config $CFG --url /healthcheck
apimgr-policy-debugger debug    --config $CFG --url /api/x --step
apimgr-policy-debugger test     --config $CFG tests/ --junit junit.xml
```

## Configuração de entrada

| Entrada | Exemplo |
|---|---|
| Arquivo `.fed` | `--config ~/deploy/QuickStart.fed` |
| Pacotes `.pol` + `.env` (mesclados como no `projdeploy`) | `--config app.pol --env prod.env` |
| Só `.pol` | `--config app.pol` (sem certificados/usuários do `.env`) |
| Configuração implantada de grupo | `--config <apigateway>/groups/<grupo>/conf/<config-id>` |
| Store YAML (`yamles`) | `--config ~/yaml/MinhaConfig` |
| URL de store | `federated:file:///…/configs.xml`, `yaml:file:///…` |

- Pacotes são abertos com as classes da própria Axway (`DeploymentArchive`, `PolicyArchive`,
  `EnvironmentArchive`) e extraídos uma vez para `$TMP/apimgr-policy-debugger-<usuário>/archives/`.
  O arquivo original nunca é alterado.
- **Environment settings:** a `EnvSettingsStore` do pacote é aplicada às entidades; `${env.X}` vem do
  `envSettings.props` da instância — passe com `--env-props <arquivo>` (repetível; o último vence);
  `${system.X}` vem do `system.properties` da Axway. `info` mostra o que foi aplicado.
- `--passphrase` para configurações cifradas.
- Variáveis `APD_CONFIG`, `APD_ENV`, `APD_PASSPHRASE` evitam repetir as opções.

## Comandos

| Comando | Descrição |
|---|---|
| `doctor` | verifica runtime, configuração, módulos, policies globais e isolamento de rede |
| `info [-v] [--json]` | estado de cada módulo carregável (`-v` mostra a causa de falhas) e environment settings |
| `policies [texto]` | lista policies como `Container/Subcontainer/Nome` |
| `paths` | serviço, método, prefixo e policy principal de cada path de listener |
| `run` | executa um request e imprime trace + resposta |
| `debug` | como `run`, com breakpoints e prompt interativo |
| `test <arquivos/dirs>…` | roda casos YAML; `--junit <arquivo>`; `-v` mostra a árvore de todos |
| `shell` | boot uma vez e lê comandos do stdin (`reload` recarrega a configuração) |
| `--version` | versão e JRE em uso |

Opções gerais: `--modules +A,-B` (força/pula módulos), `--json`, `--quiet`, `--no-diff`,
`--allow-network <hosts|all>`, `--real-routing`.

Códigos de saída: `0` pass · `1` erro inesperado · `2` uso/configuração · `3` fail/abort · `4` testes falharam ·
`5` problemas no `doctor` · `130` saída pelo debugger.

## Executando requests

### Alvo

| Opção | O que roda |
|---|---|
| `--url /x` (padrão) | path do listener resolvido pelo **maior prefixo** (como o gateway) e seu ciclo completo |
| `--path /prefixo [--service nome]` | força um path específico (desambigua serviços com o mesmo prefixo) |
| `--policy "Nome"` | uma policy isolada, com seu fault handler e o fault handler global |
| `--chain request=A,routing=B,response=C,globalRequest=global` | slots do ciclo escolhidos à mão; `global` = policy global configurada; `none` = pular. Útil para as fases do API Manager |

A policy pode ser referenciada pelo caminho completo (`Policy Library/Health Check`), pelo nome (se único) ou
por um sufixo do caminho.

### Request

Pela linha de comando:
```sh
apimgr-policy-debugger run --config $CFG -X POST --url '/api/orders?debug=true' \
  -H 'Content-Type: application/json' -H 'Authorization: Bearer abc' \
  --data '{"id": 42}' --attr authentication.subject.id=alice
```
`--data-file body.json` lê o corpo de arquivo; `--content-type` define o tipo.

Ou por arquivo (`--request req.yaml`):
```yaml
method: POST
path: /api/orders?debug=true
host: api.local                 # padrão localhost
clientIp: 10.0.0.5              # padrão 127.0.0.1
headers: { Content-Type: application/json, Authorization: "Bearer abc" }
body: '{"id": 42}'              # ou bodyFile: pedido.json (relativo ao arquivo)
attributes: { authentication.subject.id: alice }   # atributos extras na mensagem
```

### Saída

Árvore com cada policy (`▸`) e filtro (`•`): nome, tipo, resultado (`true`/`false`/`abort`), tempo e o diff de
atributos (`+` criado, `~` alterado, `-` removido). Abort (`✖`) e fault handler (`↳`) aparecem no ponto em que
ocorrem. Depois, o resumo (`PASS|FAIL|ABORT`, modo, alvo, status, tempo), o motivo da falha, as chamadas de
backend, conexões bloqueadas, headers e corpo da resposta.

`--quiet` só o resumo · `--no-diff` sem diffs (mais rápido) · `--json` resultado completo para máquina.

## Ciclo de vida emulado

Reproduz o `HTTPPlugin` e o `InvocationEngine` da Axway (evidências em
[docs/lifecycle-contract.md](docs/lifecycle-contract.md)):

```
request ─► Global Request Policy ─► Custom Request Policy ─► Path Specific Policy ─► Custom Response Policy ─► Global Response Policy ─► resposta
                     │ false/abort em qualquer etapa
                     ▼
         fault handler da policy ─► (sem fault handler) Global Fault Handler / last-chance
```

- A cadeia só avança se a policy anterior retornar `true`.
- `false` → fault handler da policy (ou last-chance) e a cadeia **para** (response não roda).
- Abort (`CircuitAbortException`) → fault handler da policy; sem ele, Global Fault Handler / last-chance.
- Resposta como `HTTPPlugin.sendResponse`: `http.response.status` (padrão 500), `http.headers` sem headers de
  hop + `http.response.headers` + headers do corpo, `content.body`.
- O request entra com os atributos que o listener cria: `http.request.uri/path/verb/version/hostname/ip`,
  `http.headers`, `content.body`, `http.request.incoming.path`, `resolved.to.path`, `message.protocol.type`…

## Backend virtual e mocks

Filtros que abrem conexão de roteamento (`Connect to URL`, `Connection`) **não saem para a rede**. O apd avalia
URL, método, headers e body com os seletores do próprio filtro, responde com o mock e grava a resposta com o
`Response.buildReply` da Axway — os mesmos atributos de uma resposta real (`http.response.status/info/version`,
`http.headers`, `content.body`, `http.destination.*`, `filter.connect.to.url.result`, `http.response.time`).
Static e Dynamic Router rodam normalmente (só definem o destino).

```yaml
# mocks.yaml (--mock, repetível) ou bloco `mocks:` num caso de teste
- url: http://heroes.org/productService/REST/GetProducts   # * em host, path e query
  method: GET                                              # opcional (qualquer)
  status: 200                                              # padrão 200
  reason: OK                                               # opcional
  headers: { Content-Type: application/json }
  body: '[{"id":1}]'                                       # ou bodyFile: resposta.json
  delayMs: 50                                              # latência simulada
- url: http://slow.example/*
  error: timeout     # 504 + abort com classificação de timeout, como o filtro real
- url: http://down.example/*
  error: refused     # falha de conexão (+ Connection Failure Policy, se configurada)
```

Destino sem mock = conexão recusada (`ERROR_CONNECTION_FAILED`), com a mensagem indicando o mock que falta.

## Testes automatizados

Um arquivo pode conter um caso ou uma lista; diretórios são varridos (`*.yaml`, `*.yml`).

```yaml
- name: heroes devolve o payload do backend
  request: { method: GET, path: /heroes }        # alvo: path (padrão) | policy: | chain: | path: + service:
  mocks:
    - url: http://heroes.org/productService/REST/GetProducts
      headers: { Content-Type: application/json }
      body: '[{"id":1,"name":"Cape"}]'
  force: ["Validate*=false"]                     # opcional: força resultado de filtros
  expect:
    outcome: pass
    status: 200
    headers: { Content-Type: application/json }
    bodyContains: '"Cape"'
    attributes: { filter.connect.to.url.result: SUCCESS }
    filters: [Parse REST Operation, "Connect to*", Reflect Message]
    notFilters: [Return HTTP Error*]
    faultHandler: false
    backend:
      - { method: GET, url: "heroes.org/productService/*", count: 1 }
```

| Asserção | Significado |
|---|---|
| `outcome` | `pass` / `fail` / `abort` |
| `status` | status final da resposta |
| `headers` | valor exato ou regex, por header (case-insensitive) |
| `body` / `bodyContains` / `bodyMatches` | igual / contém / regex |
| `attributes` | valor final de atributos da mensagem (`null` = ausente) |
| `filters` | filtros executados **nesta ordem** (subsequência; glob `*`) |
| `notFilters` | filtros que não podem ter executado |
| `policies` | policies raiz executadas, em ordem |
| `faultHandler` | se algum fault handler rodou |
| `maxMillis` | tempo máximo da execução |
| `backend` | chamadas aos mocks: `method`, `url` (glob sobre host+path), `count` (padrão ≥ 1), `bodyContains`, `headers` |

```sh
apimgr-policy-debugger test tests/ --config $CFG --junit junit.xml
```
Exemplos reais em [examples/quickstart/tests](examples/quickstart/tests) (QuickStart: sucesso, roteamento com mock,
backend 503, backend inacessível, timeout, abort forçado, policy isolada, cadeia explícita).

## Debugger interativo

```sh
apimgr-policy-debugger debug --config $CFG --url /heroes --mock m.yaml \
  -b "Connect to*" -b policy:GetProducts -b abort
```

Breakpoints: nome de filtro (glob), `policy:<nome>` (primeiro filtro da policy), `abort`, `fault`;
`--step` para no primeiro filtro. No prompt `(apd)`:

| Comando | Ação |
|---|---|
| `s` / Enter | step (entra em policies chamadas) |
| `n` | next (mesmo nível) |
| `c` | continua até o próximo breakpoint |
| `p <attr>` / `attrs [prefixo]` / `body` | inspeciona a mensagem |
| `eval <seletor>` | avalia uma expressão com o motor de seletores da Axway (`${http.request.uri}`, `${env.PORT.TRAFFIC}`) |
| `set <attr> <valor>` / `unset <attr>` | edita (`int:5`, `long:5`, `bool:true`, `null`) |
| `force true\|false\|abort [msg]` | decide o resultado do filtro que vai rodar |
| `bt` / `trace` | pilha atual / árvore até aqui |
| `b <bp>` / `d <bp>` / `q` | adiciona / remove breakpoint / sai |

`--force '<filtro>=true|false|abort[:mensagem]'` faz o mesmo sem prompt (em `run`, `debug` e testes).

## Modo shell

```sh
apimgr-policy-debugger shell --config $CFG
apd> paths
apd> run --url /healthcheck --quiet
apd> test tests/
apd> reload                       # relê a configuração (~0,7 s)
apd> exit
```
Aceita qualquer comando sem `--config`. Ideal para iterar sobre uma policy ou rodar muitos requests.

## Sandbox e limites

| Aspecto | Comportamento |
|---|---|
| Roteamento HTTP | backend virtual; nada sai do processo |
| Rede Java (LDAP, JDBC, SMTP, JMS, clientes HTTP Java) | **bloqueada** exceto loopback; `--allow-network host1,host2` ou `all`. Tentativas aparecem como `sandbox blocked:` |
| Módulos carregados | todos os de configuração (certificados, KPS, caches, stylesheets, schemas, token finders, global properties…) |
| Módulos não carregados | listeners e serviços de rede/fundo (HTTP, JMS, FileService, OPDb, métricas, eventos, alertas, Sentinel, ZeroDowntime) |
| Runtime do API Manager | não emulado (depende do gateway licenciado e de Cassandra); fases testáveis com `--chain` |
| Camada do listener | CORS/HSTS/WAF de listener, TLS/mTLS de entrada e parse HTTP nativo não existem — o request entra já como atributos |
| `--real-routing` | usa o cliente HTTP nativo contra servidores mock locais (namespace com `/etc/hosts` privado; requer root e licença com conexões irrestritas) |

O apd não altera nem contorna o licenciamento do produto: o que é amarrado à licença é emulado fora do produto
ou fica como limite acima. Lacunas conhecidas: [docs/roadmap.md](docs/roadmap.md).

## Desempenho

Ubuntu 20.04 (WSL2), configuração QuickStart (206 policies):

| Operação | Tempo |
|---|---|
| boot do runtime + índice | ~5–6 s (uma vez por processo) |
| `shell` → `reload` | ~0,7 s |
| caso de teste típico | 1–80 ms (o primeiro fault handler SOAP ~0,5 s, inicialização única) |
| 63 paths da QuickStart no `shell` | ~12,5 s incluindo boot |
| memória | ~0,5 GB (`APD_HEAP` ajusta o teto, padrão 1 GB) |

## Desenvolvimento

```sh
sh build.sh          # compila src/apd contra os jars do runtime → ext/lib/apimgr-policy-debugger.jar
sh package.sh        # dist/apimgr-policy-debugger-<versão>.tar.gz
```

Verificação antes de commitar (detalhes em [docs/operations.md](docs/operations.md)):
```sh
bin/apimgr-policy-debugger test examples/quickstart/tests --config <config QuickStart>
bin/apimgr-policy-debugger doctor --config <config QuickStart>
sh probes/fresh_clone_check.sh <config QuickStart>
```

Estrutura:
```
apimgr-policy-debugger/
├─ AGENTS.md, CLAUDE.md   contexto para agentes de IA
├─ bin/                   apimgr-policy-debugger (CLI), setup.sh (instala o runtime)
├─ conf/                  apimgr-policy-debugger.xml — descritor <JavaProgram> do vshell; runtime.env (gerado)
├─ src/apd/               código Java (pacote apd)
├─ examples/quickstart/   mocks e casos de teste
├─ docs/                  documentação técnica
├─ docker/                Dockerfile + build.sh
├─ probes/                scripts de investigação e verificação
├─ build.sh, package.sh   build e empacotamento
└─ (fora do git) re/ código Axway descompilado · runtime/ config de exemplo · build/ dist/ ext/lib/*.jar
```

## Documentação técnica

| Documento | Conteúdo |
|---|---|
| [AGENTS.md](AGENTS.md) | ponto de entrada para agentes de IA: regras, mapa do código, validação |
| [docs/architecture.md](docs/architecture.md) | componentes, fluxo de execução, invariantes, pontos de extensão |
| [docs/decisions.md](docs/decisions.md) | decisões de arquitetura (ADR) |
| [docs/lifecycle-contract.md](docs/lifecycle-contract.md) | semântica do ciclo de vida da Axway com evidências |
| [docs/axway-internals.md](docs/axway-internals.md) | jars, classes, módulos e atributos da Axway |
| [docs/operations.md](docs/operations.md) | ambiente, build, verificação, problemas conhecidos |
| [docs/implementation-history.md](docs/implementation-history.md) | histórico da implementação |
| [docs/roadmap.md](docs/roadmap.md) | lacunas, melhorias e estudo de reescrita |
