# Internos da Axway API Gateway 7.7 relevantes para o apd

> Referência do que foi levantado da instalação 7.7.0.20240228 (jars, classes, módulos, atributos).
> Evidências de comportamento do ciclo de vida: [lifecycle-contract.md](lifecycle-contract.md).
> Código descompilado local: `re/src/<jar>/` (fora do git; regenerar com Vineflower).

## 1. Layout da instalação usado pelo apd

| Caminho (`$AXWAY_HOME`) | Conteúdo |
|---|---|
| `Linux.x86_64/jre` | JRE Azul Zulu 11.0.19 |
| `Linux.x86_64/bin/vshell` | launcher nativo (lê descritores XML `<JavaProgram>`, `<NetService>`) |
| `Linux.x86_64/lib/*.so` | JNI: `libvjni.so`, `libHTTP.so`, OpenSSL, libxml… |
| `Linux.x86_64/java/*.jar` | jars específicos da plataforma |
| `platform` | symlink → `Linux.x86_64` |
| `posix/lib/venv` | script de ambiente (`LD_LIBRARY_PATH`, OpenSSL, `ulimit -c unlimited`) |
| `posix/bin/{jython,yamles,startinstance,...}` | ferramentas; `jython` = `vshell -e system/conf/jython.xml` |
| `system/conf/jvm.xml` | JVM settings + classpath (inclui `$VINSTDIR/ext/lib` primeiro) |
| `system/conf/esproviders.xml` | providers: `FileStore`, `FederatedEntityStore`, `YamlEntityStore` |
| `system/conf/templates/` | `Factory/Blank` `.fed/.pol/.env` + YAML; `PrimaryStore.xml` com o typeset |
| `system/lib`, `plugins`, `modules`, `jce` | jars Java |
| `conf/openssl.cnf`, `conf/licenses/` | `OPENSSL_CONF`; licenças (não usadas pelo apd) |

## 2. Jars e classes-chave

| Jar | Classes |
|---|---|
| `vordel-core-runtime` | `com.vordel.circuit.{InvocationEngine, Message, MessageListener, MessageProcessor, Filter, FilterContainerImpl, CircuitImpl, CircuitCache, CircuitChainProcessor, SyntheticCircuitChainProcessor, CircuitReference, SoftCircuitReference, CircuitDelegateProcessor, GlobalProperties, InvocationContext, CircuitAbortException, CircuitTracker}`, `com.vordel.dwe.{CorrelationID, NativeModule, SystemSettings, SettingStore}`, `com.vordel.runtime.interfaces.{ServiceHelper, CircuitService}` |
| `vordel-core-precipitate` | `com.vordel.precipitate.{SolutionPack, ESSolutionPack}`, `com.vordel.dwe.SolutionPackModule` |
| `vordel-core-circuit` | `com.vordel.dwe.ServiceCircuitCache`, `com.vordel.circuit.CircuitServiceImpl`, `com.vordel.circuit.net.{ConnectionProcessor, ConnectToURLProcessor, ConnectionFilter, DynamicRouterProcessor, ...}` |
| `vordel-core-http` | `com.vordel.dwe.http.{HTTPPlugin, HTTPMessage, ServerTransaction, Response, HTTPProtocol}` |
| `vordel-apigateway` | `com.vordel.dwe.Service` (NetService), `com.vordel.runtime.ServiceHelperImpl`, `ServerLicenseChecker` |
| `vordel-apimanager` | `com.vordel.apiportal.config.PortalConfiguration` (checa `LicenseService`) |
| `vordel-core` | `com.vordel.log.trace.TraceLogger`, `com.vordel.system.MimeTypes` |
| `plugins/vordel-common` | `CoreServiceRegistry`, `ServiceRegistry`, `com.vordel.common.Config` (`getUserConf` = `$VINSTDIR/conf`) |
| `plugins/vordel-config` | `ConfigContext`, `LoadableModule` |
| `plugins/es-core` | `EntityStoreFactory`, `EntityStore`, `EntityStoreDelegate`, `ShorthandKeyFinder`, `com.vordel.es.xes.PortableESPK` |
| `plugins/env-expand-store` | `com.vordel.store.util.env.{EnvExpandEntityStore, EnvExpand}` |
| `plugins/fed-archive` | `com.vordel.archive.fed.{DeploymentArchive, PolicyArchive, EnvironmentArchive, PolEnvMerger}` |
| `plugins/yaml-entity-store` | `com.axway.gw.es.yaml.*` (store YAML, base do `yamles`) |
| `jce/dispose` | `com.vordel.dispose.{Disposer, Disposable}` |
| `jce/ossljce` | `com.vordel.security.openssl.*` |
| `modules/jackson-*` | Jackson 2.16 (JSON/YAML) usado pelo apd |

## 3. Módulos carregáveis (QuickStart) e tratamento no apd

| Tratamento | Tipos |
|---|---|
| **carregados** | WebServiceRepository, CacheManager, CRLRetriever, SessionManager, JDBCDriverSet, DbConnectionGroup, TFXSLTMappingGroup, StylesheetGroup, NamespacesConfiguration, SiteMinderConnectionSet, ClientRegistrySettings, UserStore, Certificates, KeyPairs, KPSRoot, LogManager, OAuth2StoresGroup, JSONSchemaGroup, SunAccessManagerSettings, ProxyServerGroup, ICAPServerGroup, SMTPServerGroup, GSSInitiators, GSSAcceptors, PGPKeyPairs, JavaInterfaceProviders, ElementSpecifiers, TokenFinderSet, GlobalProperties |
| **metade Java** | SystemSettings (`SettingStore` populado; parte nativa ausente) |
| **nativos (sem NetService)** | MimeTypeGroup, CORSGroup (sem estado Java relevante) |
| **pulados** (`NETWORK_MODULES`) | HTTP, FileService, JMSServiceGroup, SolutionPackLoader, AmaModule, OPDbConfig, RealtimeMonitoring, ZeroDowntime, ExternallyManagedTopology, RendezvousDaemonGroup, RemoteHost, HSTSProfileGroup, ParserFeatureGroup, WAFProfileGroup, SentinelServerGroup, TrackedObjects, AlertManager, EventLog, OpenTrafficEventLog, AccessLogger, OESSecurityRuntimeLoadableModule, PassPortKeyStoreManager, PassPortClientCache, PortalConfiguration |

## 4. Atributos de mensagem relevantes

| Momento | Atributos |
|---|---|
| entrada (HTTPMessage/HTTPPlugin) | `content.body`, `http.headers`, `http.request.uri` (URI), `http.request.path`, `http.request.verb`, `http.request.version`, `http.request.hostname`, `http.request.ip`, `http.request.incoming.path`, `resolved.to.path`, `message.protocol.type`, `message.source`; gerados sob demanda: `http.request.url`, `http.querystring`, `http.raw.querystring` |
| roteamento | `http.destination.{host,port,protocol}`, `filter.connect.to.url.result` (`ConnectionProcessor$ProcessorResult`), `filter.connect.to.url.failure.description`, `http.response.time`, `http.forwarding.started/finished` |
| resposta (`Response.buildReply`) | `http.response.version`, `http.response.status`, `http.response.info`, `http.headers` (substituído pelos headers da resposta), `content.body` |
| falha | `circuit.failure.reason`, `circuit.exception` |
| globais | `system.policy.request`, `system.policy.response`, `system.policy.faulthandler` (via `GlobalProperties`, fallback de `Message.get`) |
| saída (`sendResponse`) | `http.response.status` (padrão 500), `http.response.info`, `http.headers` − `X-CorrelationID/Origin/Access-Control-Request-*/Strict-Transport-Security` + `http.response.headers` |

## 5. Pontos amarrados à licença / NetService

| Ponto | Evidência | Efeito no apd |
|---|---|---|
| NetService (`vshell conf/service.xml`) | "NO PRODUCT LICENSE FOUND … Needs a valid license to run" | não usado |
| Cliente HTTP nativo | "Restricted Mode can not connect over plain sockets"; `ServerLicenseChecker.hasFeature("unrestricted")` | `VirtualBackend` |
| `NativeModule.loadN` | NPE "null reference to native" sem pai nativo | módulos nativos ficam `native` |
| `PortalConfiguration.configure` | `LicenseService.checkPortalLicense()` | API Manager runtime fora |

## 6. Números de referência

- Métodos JNI: **806 em 141 classes** (`security.openssl` 183, `dwe.http` 148, `dwe` 113, `jaxprovider.libxml` 88,
  `circuit.oracle` 54, `system` 42, …). `Message`, `Body`, `ContentSource`, `Disposer` são Java puro.
- Typeset: **481 tipos de entidade**, **235 filtros concretos**.
- QuickStart: 881 instâncias de filtro, 102 tipos; top 10 = 60,3%, top 20 = 75,9%, top 40 = 88,4%.
  Mais usados: SetAttributeFilter 140, CircuitDelegateFilter 106, ChangeMessageFilter 59, Reflector 54,
  SaveToFileFilter 42, ConnectToURLFilter 34, CompareAttributeFilter 30, RedirectFilter 25, AddHTTPHeaderFilter 21.
- QuickStart: 206 policies, 63 paths de listener.
