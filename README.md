# apimgr-policy-debugger

Sandbox para **executar, depurar e testar policies do Axway API Gateway 7.7** sem uma instância do gateway.
As policies rodam no **motor real da Axway** (`InvocationEngine`, filtros, JRE e bibliotecas JNI do produto),
carregado dentro do `vshell` com um bootstrap próprio — sem NetService, sem listeners, sem Node Manager.

```
apimgr-policy-debugger run --config QuickStart.fed --url /heroes --mock heroes-mock.yaml

▸ Parse REST Operation  pass  21.6 ms
  • Set Service context                      [ServiceContextFilter]       true  0.17 ms
  • Parse REST Operation                     [SwitchFilter]               true  25.32 ms
    ▸ GetProducts  pass  21.6 ms
      • Connect to Heroes' REST Service      [ConnectToURLFilter]         true  7.79 ms
          + http.response.status = 200
          + filter.connect.to.url.result = SUCCESS
      • Reflect Message                      [Reflector]                  true  13.65 ms

PASS  path Default Services /heroes  status=200  116.9 ms
  backend: GET heroes.org/productService/REST/GetProducts -> 200
```

## Plataforma

**Somente Linux x86_64.** O runtime da Axway 7.7 (JRE, `vshell` e bibliotecas nativas `.so`) existe só para
Linux, e o apd roda dentro dele. Qualquer Linux x86_64 com glibc compatível serve: máquina física, VM, WSL2
(é um Linux comum para o apd) ou contêiner. Testado em Ubuntu 20.04.

## Distribuição e instalação

O pacote (`dist/apimgr-policy-debugger-<versão>.tar.gz`, ~100 KB, gerado por `package.sh`) **não contém nada da
Axway**. O setup instala o runtime a partir da instalação Axway 7.7 **de quem usa**, copiando só o necessário
(`Linux.x86_64`, `system`, `posix`, `conf`: ~650 MB).

```sh
tar -xzf apimgr-policy-debugger-1.0.0.tar.gz -C ~/tools
sudo sh ~/tools/apimgr-policy-debugger-1.0.0/bin/setup.sh /caminho/Axway/apigateway    # ou o .tar.gz da Axway
~/tools/apimgr-policy-debugger-1.0.0/bin/apimgr-policy-debugger doctor --config MinhaConfig.fed
```
O destino padrão é `/opt/axway/apigateway` (2º argumento muda); o local fica gravado em `conf/runtime.env`.
`AXWAY_HOME` sobrescreve. Dica: coloque `bin/` no `PATH`.

**Docker** (CI):
```sh
sh docker/build.sh /caminho/opt-Axway.tar.gz                # imagem apimgr-policy-debugger:1.0.0
docker run --rm -v "$PWD:/work" apimgr-policy-debugger:1.0.0 test tests/ --config MinhaConfig.fed --junit junit.xml
```
A imagem contém o runtime Axway: mantenha-a em registry privado.

**Build a partir do código** (JDK 11+, ex.: `openjdk-17-jdk-headless`): `sh build.sh` compila contra os jars do
runtime instalado (`AXWAY_HOME`/`conf/runtime.env`); `sh package.sh` gera o `.tar.gz`.

## Configuração aceita

| Entrada | Exemplo |
|---|---|
| Arquivo `.fed` | `--config ~/deploy/QuickStart.fed` |
| Pacotes `.pol` + `.env` (mesclados como no `projdeploy`) | `--config app.pol --env prod.env` |
| Só `.pol` | `--config app.pol` (sem certificados/usuários do `.env`) |
| Diretório com `configs.xml` (grupo implantado) | `--config .../groups/group-2/conf/<esid>` |
| Store YAML (`yamles`) | `--config .../yaml/MinhaConfig` |
| URL de store | `federated:file:///...` / `yaml:file:///...` |

Arquivos `.fed`/`.pol`/`.env` são abertos com as classes de arquivo da própria Axway (`DeploymentArchive`,
`PolicyArchive`, `EnvironmentArchive`) e extraídos uma vez para um cache (`/tmp/apimgr-policy-debugger-<usuário>/`);
o original nunca é alterado.

**Environment settings**, como no gateway:
- a `EnvSettingsStore` do pacote é aplicada às entidades na leitura (`EnvExpandEntityStore`);
- `${env.X}` vem do `envSettings.props` da instância: passe com `--env-props <arquivo>` (repetível, o último vence);
  `conf/envSettings.props` do apimgr-policy-debugger é lido por padrão se existir; `${system.X}` vem do `system.properties` da Axway.
- `info` mostra o que foi aplicado; no debugger, `eval ${env.X}` mostra o valor resolvido.

`--passphrase` para configs criptografadas. Variáveis `APD_CONFIG`, `APD_ENV`, `APD_PASSPHRASE` evitam repetir opções.

## Comandos

| Comando | Uso |
|---|---|
| `doctor` | checa runtime, módulos e isolamento do sandbox |
| `info [-v]` | status de cada módulo carregável (`-v` mostra a causa de falhas) |
| `policies [texto]` | lista policies (`Container/Subcontainer/Nome`) |
| `paths` | paths dos listeners → policy principal |
| `run` | executa um request e imprime trace + resposta |
| `debug` | como `run`, com breakpoints e prompt interativo |
| `test <arquivos/dirs>` | roda casos YAML (`--junit out.xml` para CI) |
| `shell` | sobe uma vez (~5 s) e executa comandos em sequência; `reload` recarrega a config (<1 s) |

### Alvo da execução

| Opção | Ciclo executado |
|---|---|
| `--url /x` (padrão) | path do listener resolvido pelo maior prefixo, igual ao gateway: **Global Request → Custom Request → Path Specific → Custom Response → Global Response** |
| `--path /prefixo [--service nome]` | força um path específico |
| `--policy "Nome"` | uma policy isolada, com seu fault handler e o fault handler global |
| `--chain request=A,routing=B,response=C,globalRequest=global` | monta os slots do ciclo à mão (`global` = policy global configurada, `none` = pular). Use para as fases do API Manager (request/routing/response/fault handler) |

### Request

`-X POST --url '/api/x?id=1' -H 'Content-Type: application/json' --data '{"a":1}'`
ou `--data-file body.json`, `--attr nome=valor` (atributos extras na mensagem) ou `--request req.yaml`:

```yaml
method: POST
path: /api/orders?debug=true
headers: { Content-Type: application/json, Authorization: "Bearer abc" }
body: '{"id": 42}'
attributes: { authentication.subject.id: alice }
```

### Saída

Árvore com cada policy/filtro, resultado (`true`/`false`/`abort`), tempo e **diff de atributos** por filtro
(`+` criado, `~` alterado, `-` removido). `--json` para máquina, `--quiet` só o resumo, `--no-diff` mais rápido.
Código de saída: `0` pass, `3` fail/abort, `4` testes falharam, `2` erro de uso.

## Ciclo de vida emulado

Reproduz `HTTPPlugin` + `SyntheticCircuitChainProcessor` + `InvocationEngine` (detalhes e evidências em
[`docs/lifecycle-contract.md`](docs/lifecycle-contract.md)):

- A cadeia avança **somente** se a policy anterior retornar `true`.
- Policy retorna `false` → fault handler da policy (ou last-chance) e a cadeia **para** (response não roda).
- `CircuitAbortException` → fault handler da policy; sem ele → **Global Fault Handler** / last-chance.
- Resposta montada como `HTTPPlugin.sendResponse`: `http.response.status` (padrão 500), `http.headers` +
  `http.response.headers` + headers do body, `content.body`.
- O request de entrada recebe os mesmos atributos que o listener cria (`http.request.uri/path/verb`,
  `http.headers`, `content.body`, `resolved.to.path`, …).

## Backend virtual (roteamento)

Filtros de roteamento que abrem conexão (`Connect to URL` e `Connection`) **não saem para a rede**. Static e
Dynamic Router rodam normalmente (só definem `http.destination.*`) e o `Connection` seguinte é atendido pelo mock.
O apimgr-policy-debugger avalia URL, método, headers e body com os seletores do próprio filtro, entrega a resposta do mock e a
grava com o `Response.buildReply` da Axway — os mesmos atributos de uma resposta real
(`http.response.status/info/version`, `http.headers`, `content.body`, `http.destination.*`,
`filter.connect.to.url.result`, `http.response.time`).

```yaml
# mocks.yaml (--mock) ou bloco `mocks:` num caso de teste
- url: http://heroes.org/productService/REST/GetProducts   # * em host, path e query
  method: GET                                              # opcional
  status: 200
  headers: { Content-Type: application/json }
  body: '[{"id":1}]'          # ou bodyFile: resposta.json
  delayMs: 50                 # latência simulada
- url: http://slow.example/*
  error: timeout              # 504 + abort (classificação de timeout), como o filtro real
- url: http://down.example/*
  error: refused              # falha de conexão (+ "Connection Failure Policy" se configurada)
```

Destino sem mock = conexão recusada (`ERROR_CONNECTION_FAILED`), com a mensagem indicando o mock que falta.

## Testes

```yaml
- name: heroes devolve o payload do backend
  request: { method: GET, path: /heroes }        # ou policy: / chain: / path:
  mocks:
    - url: http://heroes.org/productService/REST/GetProducts
      headers: { Content-Type: application/json }
      body: '[{"id":1,"name":"Cape"}]'
  force: ["Validate*=false"]                     # opcional: força resultado de filtros
  expect:
    outcome: pass                                # pass | fail | abort
    status: 200
    headers: { Content-Type: application/json }  # valor exato ou regex
    bodyContains: '"Cape"'                       # ou body / bodyMatches (regex)
    attributes: { filter.connect.to.url.result: SUCCESS }
    filters: [Parse REST Operation, "Connect to*", Reflect Message]   # subsequência, glob
    notFilters: [Return HTTP Error*]
    policies: [Parse REST Operation]             # policies raiz executadas, em ordem
    faultHandler: false
    maxMillis: 200
    backend:
      - { method: GET, url: "heroes.org/productService/*", count: 1, headers: { Accept: "*/*" } }
```

Um arquivo pode ter um caso ou uma lista. Diretórios são varridos (`*.yaml`). Exemplos em
[`examples/quickstart/tests`](examples/quickstart/tests).

## Debugger

```
apimgr-policy-debugger debug --url /heroes --mock m.yaml -b "Connect to*" -b policy:GetProducts -b abort
```

Breakpoints: nome de filtro (glob), `policy:<nome>` (primeiro filtro da policy), `abort`, `fault`; `--step` para
no primeiro filtro. No prompt `(apimgr-policy-debugger)`:

| Comando | Ação |
|---|---|
| `s` / Enter | step (entra em policies chamadas) |
| `n` | next (mesmo nível) |
| `c` | continua até o próximo breakpoint |
| `p <attr>` / `attrs [prefixo]` / `body` | inspeciona a mensagem |
| `eval <seletor>` | avalia uma expressão com o motor de seletores da Axway (`${http.request.uri}`, `${env.PORT.TRAFFIC}`) |
| `set <attr> <valor>` / `unset <attr>` | edita (`int:5`, `bool:true`, `null`) |
| `force true\|false\|abort [msg]` | decide o resultado do filtro que vai rodar |
| `bt` / `trace` | pilha atual / árvore até aqui |
| `b <bp>` / `d <bp>` / `q` | adiciona / remove breakpoint / sai |

`--force '<filtro>=true|false|abort[:mensagem]'` faz o mesmo sem prompt (em `run`, `debug` e testes).

## Sandbox e limites

| Aspecto | Comportamento |
|---|---|
| Rede HTTP de roteamento | backend virtual (nada sai do processo) |
| Rede Java (LDAP, JDBC, SMTP, JMS, clientes HTTP Java) | **bloqueada**, exceto loopback; `--allow-network host1,host2` ou `all`. Tentativas aparecem como `sandbox blocked:` |
| Módulos carregados | todos os de configuração (certificados, KPS, caches, stylesheets, schemas, token finders, global properties…) |
| Módulos não carregados | listeners/rede/serviços de fundo (HTTP, JMS, FileService, OPDb, métricas, eventos, alertas, Sentinel, ZeroDowntime) |
| Runtime do API Manager | **não emulado**: `PortalConfiguration` exige o `LicenseService` do gateway licenciado e Cassandra. Fases testáveis via `--chain` |
| Camada do listener | CORS/HSTS/WAF do listener, TLS/mTLS de entrada e parse HTTP nativo não existem — o request entra já como atributos |
| `--real-routing` | usa o cliente HTTP nativo contra servidores mock locais (namespace com `/etc/hosts` privado, requer root). **Exige licença com conexões irrestritas**: sem licença o cliente nativo fica em *Restricted Mode* e recusa conexões |

A ferramenta não altera nem contorna o licenciamento do produto: o que é amarrado à licença fica fora do sandbox
e é emulado do lado de fora (backend virtual) ou declarado como limite acima.

## Desempenho (Ubuntu 20.04 em WSL2, QuickStart: 206 policies)

| Operação | Tempo |
|---|---|
| boot do runtime + índice | ~5–6 s (uma vez por processo) |
| `shell` → `reload` | ~0,7 s |
| caso de teste típico | 1–80 ms (o primeiro fault handler SOAP ~0,5 s, inicialização única) |
| memória | ~0,5 GB (`APD_HEAP` ajusta o teto, padrão 1 GB) |

## Estrutura

```
apimgr-policy-debugger/
├─ bin/            apimgr-policy-debugger (CLI), setup.sh (instala o runtime)
├─ conf/           apimgr-policy-debugger.xml — descritor <JavaProgram> do vshell (herda jvm.xml da Axway); runtime.env (setup)
├─ docker/         Dockerfile + build.sh (imagem local com o runtime de quem usa)
├─ build.sh / package.sh   build do jar / .tar.gz de distribuição (dist/)
├─ ext/lib/        apimgr-policy-debugger.jar (VINSTDIR/ext/lib entra no classpath do produto)
├─ src/apd/        GatewayRuntime (bootstrap), Lifecycle (cadeia), ExecutionTrace (listener do motor),
│                  DebugProcessor (wrapper de filtros), Debugger, VirtualBackend, MockBackend,
│                  NetworkGuard, TestRunner, Report, Main
├─ docs/           lifecycle-contract.md — evidências do ciclo de vida
├─ examples/       quickstart/ (mocks e testes)
├─ re/             código descompilado usado na engenharia reversa (referência)
└─ probes/         scripts de investigação
```
