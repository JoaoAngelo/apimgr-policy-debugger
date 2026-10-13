# Arquitetura do apd (apimgr-policy-debugger)

> Documento de referência técnica. Público: desenvolvedores e agentes de IA que vão manter ou estender o apd.
> Para o *porquê* das escolhas veja [decisions.md](decisions.md); para o *como chegamos aqui*,
> [implementation-history.md](implementation-history.md); para detalhes do produto Axway,
> [axway-internals.md](axway-internals.md) e [lifecycle-contract.md](lifecycle-contract.md).

## 1. Ideia central

O apd **não reimplementa** o API Gateway. Ele hospeda o **motor de policies da própria Axway** num processo
próprio e substitui apenas o que é amarrado ao processo do gateway (NetService), à rede ou à licença:

```
┌──────────────────────────── processo vshell (runtime Axway, Linux x86_64) ─────────────────────────────┐
│ JRE Axway 11.0.19 + bibliotecas JNI (.so) + classpath do produto (jvm.xml)                              │
│                                                                                                        │
│  apd.Main ──► GatewayRuntime ──► EntityStore (federated/yaml) ─► EnvExpandEntityStore                   │
│     │              │                ESSolutionPack (ConfigContext) + DebugProcessor.Cache               │
│     │              │                LoadableModules seletivos (certs, KPS, caches, GlobalProperties…)   │
│     │              └─► CoreServiceRegistry: ApdServiceHelper (ServiceHelper), CircuitServiceImpl         │
│     │                                                                                                  │
│     ├─► PolicyIndex (policies e paths de listener)                                                     │
│     ├─► Lifecycle ──► Message (RequestSpec) ──► SyntheticCircuitChainProcessor / InvocationEngine      │
│     │                    │                        (código Axway: cadeia, fault handlers, filtros)       │
│     │                    └── MessageListener: ExecutionTrace ──► Debugger (hooks)                       │
│     │      cada filtro = DebugProcessor(real) ──► Outcome forçado │ VirtualBackend (routing) │ real      │
│     ├─► TestRunner / Report / shell                                                                    │
│     └─► NetworkGuard (SecurityManager: bloqueia rede Java não-local)                                   │
└────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

Tudo que está à direita de `Lifecycle` (cadeia, `InvocationEngine`, filtros, seletores, `Response.buildReply`)
é **código Axway executando de verdade**. O apd só monta o contexto, observa e intercepta.

## 2. Como o processo sobe

1. `bin/apimgr-policy-debugger` (sh):
   - lê `conf/runtime.env` (gravado por `bin/setup.sh`) → `AXWAY_HOME`; padrão `/opt/axway/apigateway`;
   - se root e `unshare -m` permitido: reexecuta-se num **mount namespace privado** com `/etc/hosts` próprio
     (`APD_HOSTS_FILE`), usado só por `--real-routing`;
   - exporta `VDISTDIR=$AXWAY_HOME`, `VINSTDIR=$APD_HOME`;
   - faz source de `$VDISTDIR/posix/lib/venv` (ambiente Axway: `LD_LIBRARY_PATH`, OpenSSL…);
   - **`ulimit -c 0`** (o venv liga core dumps ilimitados; no WSL cada um vira um arquivo de GBs);
   - `exec vshell -Dquiet [-DapdHeap=…] -N -e conf/apimgr-policy-debugger.xml "$@"`.
2. `conf/apimgr-policy-debugger.xml` é um descritor `<JavaProgram main="apd.Main">` que inclui o
   `system/conf/jvm.xml` da Axway (classloader `com.vordel.boot.ServiceClassLoader`, todos os jars,
   propriedades de segurança XML) e define heap (`maxHeap`, padrão 1024m), `-XX:+UseSerialGC`,
   `-XX:TieredStopAtLevel=1`.
3. O jar do apd entra no classpath porque o `jvm.xml` adiciona `$VINSTDIR/ext/lib` **antes** dos jars do
   produto, e `VINSTDIR` aponta para a pasta do apd. (Um `<ClassPath>` fora de `<JVMSettings>` é ignorado
   pelo vshell — ver decisions.md D-07.)

## 3. Componentes (src/apd)

| Classe | Responsabilidade | Interage com (Axway) |
|---|---|---|
| `Main` | CLI: parse de args (`Args`), `Session` (runtime+índice reutilizáveis), comandos `doctor/info/policies/paths/run/debug/test/shell`, `--version`, `NetworkGuard.install` | — |
| `GatewayRuntime` | Bootstrap: resolve `--config` → URL de store; extrai `.fed/.pol/.env`; carrega `envSettings.props`; abre a store com `EnvExpandEntityStore`; registra serviços; cria `ESSolutionPack`; carrega módulos seletivamente; `close()` para `reload` | `EntityStoreFactory`, `EnvExpandEntityStore`, `EnvExpand`, `DeploymentArchive`/`PolicyArchive`/`EnvironmentArchive`, `ESSolutionPack`, `CoreServiceRegistry`, `CircuitServiceImpl`, `LoadableModule`, `SettingStore`/`SystemSettings` |
| `ApdServiceHelper` | Implementa `ServiceHelper` (o `ServiceHelperImpl` original depende do singleton `Service`/NetService). `getLocalPackCircuit` → pack do apd; side-channels (traffic monitor, access log, remote hosts) inertes | `ServiceHelper` |
| `PolicyIndex` | Nomeia policies como no Policy Studio (`Container/Sub/Nome`), resolve por caminho/nome/sufixo; descobre paths de listener (entidades com `uriprefix`+`filterCircuit`), longest-prefix match | `ShorthandKeyFinder`, `EntityStore` |
| `RequestSpec` | Request de entrada (YAML/CLI) → `Message` com os atributos que `HTTPMessage`/`HTTPPlugin` criam | `Message`, `CorrelationID` (JNI), `HeaderSet`, `Body`, `ContentType`, `ByteArrayContentSource` |
| `Lifecycle` | Executa: `policy` (uma policy via `InvocationEngine.processMessage`), `path` (cadeia igual a `HTTPPlugin.configureCircuits`), `chain` (slots explícitos); monta resposta como `HTTPPlugin.sendResponse`; liga `VirtualBackend` ou `MockBackend` | `SyntheticCircuitChainProcessor`, `CircuitChainFilter`, `CircuitReference`, `SoftCircuitReference`, `PortableESPK`, `EntityStoreDelegate`, `Disposer` |
| `ExecutionTrace` | `MessageListener` registrado na `Message`: recebe `preCircuitInvocation`, `pre/postFilterInvocation`, `abortedCircuitInvocation`, `preFaultHandlerInvocation`…; monta eventos com profundidade, tempo e **diff de atributos** | `MessageListener` |
| `DebugProcessor` | Wrapper de **cada** filtro (`MessageProcessor`), compartilha o mesmo `Filter` (grafo sucesso/falha intacto). `invoke`: consulta o *oracle* (forçar TRUE/FALSE/ABORT), senão `VirtualBackend` (se o real é `ConnectionProcessor`), senão o real. `Cache` = `ServiceCircuitCache` que embrulha | `MessageProcessor`, `FilterContainerImpl`, `ServiceCircuitCache` |
| `Debugger` | `Hooks` do trace + `OutcomeOracle`: breakpoints (glob de filtro, `policy:`, `abort`, `fault`), step/next/continue, REPL (`p`, `attrs`, `body`, `eval`, `set`, `unset`, `force`, `bt`, `trace`, `b`, `d`, `q`); `--force` não interativo | `Selector` (eval) |
| `VirtualBackend` | Substitui só a **transação de rede** dos filtros de roteamento: avalia URL/método/headers/body com os seletores privados do `ConnectToURLProcessor` (reflexão), casa com mocks, escreve a resposta com **`Response.buildReply` da Axway**; falhas: refused → `ERROR_CONNECTION_FAILED` (+ Connection Failure Policy), timeout → 504 + abort(5) | `ConnectionProcessor`, `ConnectToURLProcessor`, `Response`, `DelegatingProcessor.callCircuit` |
| `MockBackend` | `--real-routing`: servidores HTTP locais (`com.sun.net.httpserver`) em `127.0.0.x`, hostnames escritos no `/etc/hosts` privado; também define o modelo `Route`/`Call` usado pelo `VirtualBackend` | — |
| `NetworkGuard` | `SecurityManager` que bloqueia `connect` não-loopback (LDAP, JDBC, SMTP, clientes Java); allowlist por host (pré-resolvido para IP) ou `all` | — |
| `TestRunner` | Casos YAML (arquivo único ou lista, diretórios), `Expect` (outcome, status, headers, body*, attributes, filters em ordem, notFilters, policies, faultHandler, maxMillis, backend), JUnit XML | — |
| `Report` | Árvore colorida, resumo, JSON (`Lifecycle.Result`) | — |

## 4. Fluxo de uma execução (`run --url /heroes --mock m.yaml`)

1. `Main.run` → `NetworkGuard.install` → `Session.load` → `new GatewayRuntime(Options)`.
2. `GatewayRuntime`:
   1. `resolveStoreUrl`: `.fed` → `DeploymentArchive(f).extractContentToDir(cache)`; `.pol`(+`.env`) →
      `DeploymentArchive(PolicyArchive, EnvironmentArchive, false, passphrase)`; diretório com `configs.xml` →
      `federated:file://…`; diretório YAML → `yaml:file://…`. Cache: `$TMP/apimgr-policy-debugger-<user>/archives/<nome-tamanho-mtime>`.
   2. `Selector.addGlobalNamespace("environment", System.getenv)`.
   3. `EnvExpand.reload()` (lê `system.properties` e `$VINSTDIR/conf/envSettings.props`); `--env-props` são
      mesclados num arquivo temporário e registrados como namespace `env`.
   4. `EnvExpandEntityStore(raw, cipher, hasEnvSettings)` + `connect` (aplica a `EnvSettingsStore`).
   5. Registra `ApdServiceHelper` e `CircuitServiceImpl` no `CoreServiceRegistry`.
   6. `new ESSolutionPack(store, cipher, new DebugProcessor.Cache())`.
   7. `loadModules`: coleta `[LoadableModule]` sob `/[NetService]name=Service` e na raiz, ordena por
      `loadorder`, pula `NETWORK_MODULES` (+`--modules -X`), `load(null, type)` e depois `configure(pack, e)`.
      `NativeModule` sem NetService → estado `native`; `SystemSettings` tem a metade Java (`SettingStore`)
      populada por reflexão.
3. `PolicyIndex` indexa policies e paths.
4. `Lifecycle.run(plan, req, …, mocks, realRouting=false)` → `DebugProcessor.virtualBackend = new VirtualBackend(mocks)`.
5. `RequestSpec.toMessage` cria a `Message` (`CorrelationID` real via JNI) e o `ExecutionTrace` é adicionado
   como listener.
6. Modo `path`: `pathChain(entity)` reproduz `HTTPPlugin.configureCircuits` (refs filhas ordenadas por
   `priority` + `PATH_SPECIFIC_POLICY` no índice 2 quando há 4 refs) → `CircuitChainFilter` →
   `SyntheticCircuitChainProcessor.attach(pack)` → `invoke(m, lastChance, ctx)` → `InvocationEngine.processMessage`.
7. Cada filtro: `InvocationEngine.invokeFilter` → listener `preFilterInvocation` (hooks/breakpoint, snapshot)
   → `DebugProcessor.invoke` → oracle / virtual backend / real → `postFilterInvocation` (diff, hooks).
8. Fim: `collectResponse` (status padrão 500, headers = `http.headers` − hop headers + `http.response.headers`
   + headers do body; corpo lido com `Body.getInputStream(0)`), `Result` com trace, policies raiz,
   chamadas de backend, conexões bloqueadas.
9. `Report.print` ou JSON; código de saída: 0 pass, 3 fail/abort.

## 5. Contratos e invariantes (não quebrar)

- **Nunca** mudar a semântica do motor: o apd não decide fluxo; só `DebugProcessor` pode alterar o resultado
  de um filtro, e só quando o usuário pede (`--force`, `force` no debugger) ou no roteamento virtual.
- `DebugProcessor` deve **compartilhar o `Filter`** do processor real (`setFilter(f)`) e chamar
  `super.filterAttached` (log masks) **e** `delegate.filterAttached`. Sem isso o `configureFilter` lança erro
  de contagem de attach.
- Snapshot de diff é tirado **depois** do prompt do debugger (edições do usuário não aparecem como mudança do filtro).
- `Lifecycle.run` sempre faz `Disposer.pushForThread()/popForThread().disposeAll()` em volta da execução.
- `VirtualBackend` só intercepta `ConnectionProcessor` (inclui `ConnectToURLProcessor`). Static/Dynamic Router
  rodam reais (só setam `http.destination.*`).
- Referências de circuito criadas pelo apd usam `PortableESPK.toPortableKey(store, pk)` (FSPK direto falha:
  "ESPK cannot be FSPK").
- Policies referenciadas entre stores (ListenersStore → PrimaryStore) resolvem pelo `store.getEntity(pk).getPK()`.
- Não registrar `LicenseService` nem simular o objeto nativo do NetService (ver decisions.md D-04).
- `Main` encerra com `Runtime.halt(code)` para não esperar threads não-daemon iniciadas pelos módulos da
  Axway. (Não foi testado se `System.exit` encerra limpo dentro do vshell; não trocar sem testar.)

## 6. Pontos de extensão

| Quero… | Onde |
|---|---|
| novo comando CLI | `Main.dispatch` + `USAGE` + `Args.FLAGS` (se flag booleana) |
| nova asserção de teste | `TestRunner.Expect` + `check()` |
| interceptar outro tipo de filtro | `DebugProcessor.invoke` (padrão do `VirtualBackend.handles`) |
| novo comando do debugger | `Debugger.prompt` (switch) + texto de help |
| carregar/pular módulo por padrão | `GatewayRuntime.NETWORK_MODULES` / `loadModules` |
| novo formato de entrada | `GatewayRuntime.resolveStoreUrl` |
| novo campo de request | `RequestSpec` + `toMessage` |
| novo comportamento de mock | `MockBackend.Route` (modelo) + `VirtualBackend.invoke` |
| novo dado no resultado | `Lifecycle.Result` (+ `Report`) |

## 7. Saídas e códigos

| Código | Significado |
|---|---|
| 0 | pass / comando ok |
| 1 | erro inesperado (stack impresso) |
| 2 | erro de uso / configuração |
| 3 | run/debug terminou em fail ou abort |
| 4 | `test`: algum caso falhou |
| 5 | `doctor`: problemas encontrados |
| 130 | `q` no debugger |
