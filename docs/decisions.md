# Decisões de arquitetura (ADR)

> Formato: contexto → decisão → consequências. Antes de reverter uma decisão, leia o contexto: a maioria
> nasceu de uma falha concreta registrada em [implementation-history.md](implementation-history.md).

## D-01 — Executar o motor real da Axway em vez de reimplementar
- **Contexto:** objetivo é depurar policies com fidelidade ao gateway; reimplementar 235 tipos de filtro e a
  linguagem de seletores é trabalho de anos.
- **Decisão:** hospedar o motor da Axway (jars + JNI) e substituir só o que é do processo do gateway.
- **Consequências:** fidelidade máxima nos filtros; o apd depende de uma instalação Axway 7.7 e só roda onde o
  runtime roda (Linux x86_64).

## D-02 — Rodar dentro do `vshell` ("Modo A")
- **Contexto:** `CorrelationID`, `Trace` e outros são JNI (`libvjni.so`); um JVM comum precisaria de stubs.
- **Decisão:** descritor `<JavaProgram>` próprio incluindo `system/conf/jvm.xml`, executado pelo `vshell`.
- **Consequências:** JNI, classloader e propriedades do produto vêm de graça; sem shims. Boot ~5 s.

## D-03 — Bootstrap próprio sem NetService
- **Contexto:** `Service` (NetService) exige licença e sobe listeners; `ServiceHelperImpl` depende dele.
- **Decisão:** `GatewayRuntime` refaz o mínimo (store → serviços → `ESSolutionPack` → módulos seletivos);
  `ApdServiceHelper` implementa a interface `ServiceHelper`.
- **Consequências:** sem listeners/Node Manager; serviços globais (traffic monitor, access log) inertes.

## D-04 — Não contornar o licenciamento
- **Contexto:** licença do refenv expirada; partes do produto checam licença (NetService nativo,
  *Restricted Mode* do cliente HTTP nativo via feature `unrestricted`, `LicenseService` do API Manager).
- **Decisão:** nunca falsificar/patchar verificações de licença (sem `LicenseService` falso, sem objeto nativo
  fake, sem mexer em datas/arquivos de licença). O que depende de licença é emulado **fora** do produto ou
  fica como limite documentado.
- **Consequências:** roteamento por `VirtualBackend`; runtime do API Manager fora; `--real-routing` só com
  licença válida.

## D-05 — Ganchos de debug via `MessageListener`
- **Contexto:** `InvocationEngine` já notifica listeners em cada policy/filtro/abort/fault handler.
- **Decisão:** trace e breakpoints por `ExecutionTrace` (listener na `Message`), sem javaagent/bytecode.
- **Consequências:** zero intrusão no motor; o prompt do debugger bloqueia a thread dentro do callback.

## D-06 — Resultado forçado via wrapper (`DebugProcessor`)
- **Contexto:** listener não consegue mudar o resultado de um filtro.
- **Decisão:** o `CircuitCache` do pack embrulha cada processor; o wrapper compartilha o `Filter` e decide
  RUN/TRUE/FALSE/ABORT antes de delegar.
- **Consequências:** instrumentação sempre ligada (custo desprezível); código que faça `instanceof` do tipo
  concreto do processor vê o wrapper (não observado como problema até agora).

## D-07 — Jar via `$VINSTDIR/ext/lib`
- **Contexto:** `<ClassPath>` fora de `<JVMSettings>` é ignorado pelo vshell (classe não encontrada, segfaults).
- **Decisão:** `VINSTDIR` = pasta do apd; o `jvm.xml` da Axway já põe `$VINSTDIR/ext/lib` no classpath.
- **Consequências:** `Config.getUserConf()` = `<apd>/conf` (é onde `envSettings.props` padrão é lido e onde
  módulos podem criar `conf/kps`, `conf/tmp` — ignorados no git).

## D-08 — Carregamento seletivo de módulos
- **Contexto:** módulos de rede/listener/fundo abrem portas, threads ou exigem o NetService.
- **Decisão:** `NETWORK_MODULES` (lista fixa) são pulados; o resto é carregado na ordem `loadorder`;
  `--modules +X/-Y` ajusta. `NativeModule` sem pai → estado `native`; `SystemSettings` → só `SettingStore`.
- **Consequências:** `info -v`/`doctor` mostram o estado; filtros que dependam de módulo pulado falham de forma visível.

## D-09 — Backend virtual para roteamento
- **Contexto:** cliente HTTP nativo em *Restricted Mode* sem licença.
- **Decisão:** interceptar só `ConnectionProcessor`; avaliar o request com os seletores do filtro e escrever a
  resposta com `Response.buildReply` real; sem mock = conexão recusada.
- **Consequências:** atributos pós-roteamento iguais aos reais; não exercita o HTTP de verdade (serialização,
  TLS, retries, SSL profiles). `MockBackend` + `--real-routing` cobre isso quando houver licença.

## D-10 — Somente Linux x86_64
- **Contexto:** a Axway 7.7 só traz runtime Linux; 806 métodos JNI tornariam um porte Windows uma reescrita.
- **Decisão (do usuário):** suportar apenas Linux; WSL é tratado como Linux comum. Removidos `.cmd`/`.ps1`.
- **Consequências:** build/package em shell (`build.sh`, `package.sh`, JDK ≥ 11); git operado de dentro do Linux.

## D-11 — Distribuição sem binários Axway
- **Decisão:** pacote do apd (~100 KB) + `bin/setup.sh` que copia do runtime do próprio usuário só
  `Linux.x86_64 platform system posix conf` para `/opt/axway/apigateway` e grava `conf/runtime.env`.
- **Consequências:** instalação em ~30 s; Docker opcional gera imagem local com o runtime do usuário.

## D-12 — Abrir pacotes com as classes da Axway
- **Decisão:** `.fed`/`.pol`/`.env` via `DeploymentArchive`/`PolicyArchive`/`EnvironmentArchive` (mesmo merge do
  `projdeploy`); cache por usuário e por (nome, tamanho, mtime).
- **Consequências:** formato sempre compatível com o produto; um cache criado por root não afeta outro usuário.

## D-13 — Environment settings como no gateway
- **Decisão:** `EnvExpandEntityStore(store, cipher, hasEnvSettings)` + `EnvExpand.reload()` + `--env-props`
  mesclados (último vence).
- **Consequências:** `${env.X}` e valores da `EnvSettingsStore` resolvem igual ao gateway; sem `--env-props`,
  só `<apd>/conf/envSettings.props` (se existir) e `system.properties`.

## D-14 — Rede Java isolada por padrão
- **Decisão:** `NetworkGuard` (`SecurityManager`) bloqueia `connect` não-loopback; `--allow-network`.
- **Consequências:** sandbox não vaza para LDAP/DB/SMTP reais; Java 11 permite `SecurityManager` (em JDKs
  ≥ 18 seria preciso `-Djava.security.manager=allow`, irrelevante enquanto o runtime for o JRE 11 da Axway).
