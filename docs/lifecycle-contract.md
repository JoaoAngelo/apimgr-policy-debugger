# Contrato do ciclo de vida — Axway API Gateway 7.7.0.20240228

Fonte: instalação extraída em `extracted/opt/Axway/APIM-7.7.0/apigateway`.
Código Java descompilado com Vineflower 1.12.0 em `re/src/<jar>/` (local, fora do git; regenerar com `java -jar vineflower.jar <jar> re/src/<jar>/`).
Legenda: **[O]** observado no código/execução · **[I]** inferência · **[?]** em aberto.

## 1. Entrada HTTP → Message

| # | Passo | Evidência |
|---|---|---|
| 1.1 | **[O]** Transporte HTTP é nativo (`libHTTP.so`); expõe ao Java `ServerTransaction`, `HTTPProtocol`, `ClientTransaction`, `CorrelationID`, `HeaderSet`. | strings JNI de `Linux.x86_64/lib/libHTTP.so` |
| 1.2 | **[O]** Nativo chama `HTTPPlugin.invoke(protocol, handler, txn, CorrelationID, loopbackMsg)` por path resolvido. | `vordel-core-http/.../dwe/http/HTTPPlugin.java:117` |
| 1.3 | **[O]** Cria `HTTPMessage(this, protocol, txn, id, ...)`, adiciona `http.request.incoming.path`, `resolved.to.path` (= `uriprefix`), `dwe.protocol.loopback*`. | `HTTPPlugin.java:132-150` |
| 1.4 | **[O]** Registra o listener do Traffic Monitor (`ServiceHelper.getTrafficMonitorMessageListener()`) na Message. | `HTTPPlugin.java:152` |

## 2. Cadeia de policies por path (Relative Path / `HTTPPlugin.configureCircuits`)

| Pos | Label | Origem | Evidência |
|---|---|---|---|
| 0 | `GLOBAL_POLICY_REQ` | `SoftCircuitReference` → `system.policy.request` | `WSDLProcessor.java:29`, `HTTPPlugin.java:82` |
| 1 | `CUSTOM_POLICY_REQ` | `PortableCircuitReference` (filho, priority=1) | idem |
| 2 | `PATH_SPECIFIC_POLICY` | `filterCircuit` do path (inserido no índice 2 quando há 4 refs, senão 0) | `HTTPPlugin.java:74-89` |
| 3 | `CUSTOM_POLICY_RSP` | `PortableCircuitReference` (priority=3) | `WSDLProcessor.java:31` |
| 4 | `GLOBAL_POLICY_RSP` | `SoftCircuitReference` → `system.policy.response` | `WSDLProcessor.java:32` |

- **[O]** Refs desabilitadas ou vazias são puladas (`CRIterator.getNext`). — `SyntheticCircuitChainProcessor.java`
- **[O]** `SoftCircuitReference` resolve o PK lendo `m.get("system.policy.*")` e `ServiceHelper.getLocalPackCircuit(pk)`. — `SoftCircuitReference.java`

## 3. Semântica de execução (`InvocationEngine`)

- **[O]** `processMessage`: segue para a próxima policy da cadeia **somente se a atual retornar `true`** (stat 2). — `InvocationEngine.java:processMessage`
- **[O]** Policy retorna `false` → seta `circuit.failure.reason`, chama o fault handler da policy (ou o last-chance) e **a cadeia para**; as policies de response não rodam.
- **[O]** Filtro lança `CircuitAbortException` → `circuit.exception` e `circuit.failure.reason` gravados → fault handler da policy (`CircuitImpl.getFaultHandler()`); sem fault handler, a exceção sobe → **last-chance handler**.
- **[O]** Last-chance = `GlobalProperties["system.policy.faulthandler"]` (Global Fault Handler, envolvido em `CircuitDelegateProcessor` "Integral Fault Handler Reference"); senão `CircuitService.getLastChanceMessageProcessor()`.
- **[O]** Em cada policy: começa em `getStartProcessor()`, segue `getSuccessNode()`/`getFailureNode()` até `null`.
- **[O]** Classificações do abort: `CLASS_SERVFAIL`, `CLASS_AUTHORIZATION`, `CLASS_AUTHENTICATION`, `CLASS_REMOTE`, `CLASS_SERVTIMEOUT`. — `CircuitAbortException`

## 4. Resposta

- **[O]** `sendResponse`: status = `http.response.status` (padrão 500), texto = `http.response.info`, headers = `http.headers` (menos X-CorrelationID/Origin/CORS/HSTS) + `http.response.headers`, body = `content.body`. — `HTTPPlugin.java:224-282`
- **[O]** Grava `http.response.started` / `http.response.finished`; erro no envio → `final.status=Error`, 509.

## 5. Ganchos de debug nativos (sem instrumentação)

**[O]** `MessageListener` (via `Message.addMessageListener` ou `Message.addCreationListener` global) recebe:
`preCircuitProcessing`, `preCircuitInvocation`, `preFilterInvocation`, `postFilterInvocation(resultType 0=false/1=true/2=exception)`, `abortedCircuitInvocation`, `preFaultHandlerInvocation`, `postCircuitInvocation`, `postCircuitProcessing`, `onMessageCompletion`.
Watch de atributos: `Message.addPropertiesListener`.

## 6. Runtime nativo (Modo A)

- **[O]** `posix/bin/jython` → `vshell -e system/conf/jython.xml` sobe a JRE da Axway (11.0.19) com JNI; `CorrelationID` e `Message` reais funcionam no WSL. — `probes/native_probe.py`
- **[O]** Descritores `<JavaProgram main=...>` + `<include file="jvm.xml"/>` herdam o classpath completo e o `ServiceClassLoader`.
- **[O]** Stores suportadas: `federated:file:///.../configs.xml` e `yaml:file:///...`. — `esconnection.xml`, `skel/esconnection-yaml.xml`, `esproviders.xml`
- **[O]** Instância completa (`vshell ... conf/service.xml`) **não sobe: licença expirada em 2025-01-24**. — `probes/start_instance.sh`

## 7. Bootstrap sem NetService (implementado em `src/apd/GatewayRuntime.java`)

- **[O]** `Service` (NetService) só faz: `EntityStoreFactory.getEntityStoreForURL` + `connect`, registra serviços no `CoreServiceRegistry`, cria `ESSolutionPack(store, cipher, ServiceCircuitCache)`, `loadModules`, `loadCircuits`. — `vordel-apigateway/.../dwe/Service.java:306-480`
- **[O]** `ServiceHelperImpl` delega tudo ao singleton `Service`; o apimgr-policy-debugger registra `ApdServiceHelper` (mesma interface) apontando para o próprio pack.
- **[O]** Circuitos são configurados sob demanda por `CircuitCache.getCircuit(pk)`; filtros são criados por `CircuitCache.create` — ponto onde o apimgr-policy-debugger injeta `DebugProcessor`.
- **[O]** `system.policy.*`: `GlobalProperties` é um `LoadableModule` comum; `Message.get` cai nele quando o atributo não existe — `SoftCircuitReference` resolve as policies globais por aí.
- **[O]** `NativeModule.loadN` exige o objeto nativo pai (NetService): `SystemSettings`, `MimeTypeGroup`, `CORSGroup` não carregam. `SystemSettings` tem metade Java (`SettingStore`) que o apimgr-policy-debugger popula.
- **[O]** `PortalConfiguration.configure` chama `LicenseService.checkPortalLicense()` — runtime do API Manager fica fora do sandbox.

## 8. Roteamento

- **[O]** `ConnectToURLProcessor.invoke`: avalia `url`/`method`/`protoHeaders`/`body` (Selectors), grava `http.request.uri/path`, `http.destination.host/port/protocol`, chama `ConnectionProcessor.invoke(c, m, headers, verb, body)`, grava `http.response.time`.
- **[O]** `ConnectionProcessor.invoke`: remove `http.response.status/info`; transação nativa (`State.tryTransaction`); grava `filter.connect.to.url.result` (+ `.failure.description`); falha de conexão → `false` (+ *Connection Failure Policy* se `runPolicyOnConnectionFailure`); timeout → 504 + `CircuitAbortException(…, 5)`; resposta → `Response.buildReply` (`http.response.version/status/info`, `http.headers`, `content.body`, `http.forwarding.finished`).
- **[O]** Sem licença o cliente nativo responde *"Restricted Mode can not connect over plain sockets"* (`ServerLicenseChecker`: feature `unrestricted`). O apimgr-policy-debugger substitui só a transação (backend virtual) e usa `Response.buildReply` real.

## 9. Em aberto

- **[?]** `HTTPClientFilter` (pre/post message creation, routing, post routing circuits) não aparece nos filtros da QuickStart; não validado.
- **[?]** Websocket (incoming/outgoing policies) não emulado.
