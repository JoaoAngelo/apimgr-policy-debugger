# Histórico de implementação do apd

> Registro cronológico de como o apd foi construído (sessão de 2026-10-13), incluindo becos sem saída.
> Serve de memória para quem continuar o trabalho: muitas escolhas só fazem sentido sabendo o que falhou antes.
> Decisões formais: [decisions.md](decisions.md).

## Fase 0 — Obter a instalação da Axway

**Ponto de partida:** a pasta `API-7.7_Refenv_20240228/` com discos VMware (`.vmdk`) da VM *Axway API Gateway
7.7 Reference Environment* (CentOS 7, LVM, XFS).

1. **Descritor ausente e disco incompleto.** O `.vmdk` principal (descritor) não existia e só havia as extensões
   `-s001..s007` (27,8 GB) de um disco cuja partição LVM tinha 59,5 GB. O descritor foi reconstruído a partir
   dos cabeçalhos das extensões (`KDMV`, capacidade 8323072 setores cada). Depois o usuário trouxe as extensões
   `-s008..s016`, o descritor original e o `.vmx`.
2. **Disco C: cheio.** A conversão `qemu-img convert` para raw dentro do WSL (cujo `.vhdx` ficava no C:) zerou o C:.
   Remediação: apagar o raw, `fstrim`, `wsl --manage Ubuntu-20.04 --set-sparse true` e
   `wsl --manage Ubuntu-20.04 --move Z:\WSL\Ubuntu-20.04`. **Lição:** nada pesado no C:.
3. **Kernel do WSL não monta XFS V4** ("Deprecated V4 format (crc=0) not supported by kernel").
   Solução: `libguestfs` (`guestfish --ro`, `LIBGUESTFS_BACKEND=direct`) com `linux-image-generic` do Ubuntu
   (kernel 5.4 suporta XFS V4) lendo o `.vmdk` diretamente.
4. **Extração:** `/opt/Axway` e `/home/axway` como pastas navegáveis e como `.tar.gz` fiéis (preservam
   permissões, symlinks e FIFOs) em `Z:\Projetos\apimgateway\extracted\`. FIFOs
   (`pipe-channel-*.fifo`) não existem em NTFS, por isso falharam na cópia, mas estão no tar.

## Fase 1 — Escopo

- Pedido inicial: converter `.fed` ↔ YAML e debugar policies (routing, request, response, fault handler, ciclo
  completo) sem replicar o gateway.
- Descoberta: a 7.7.0.20240228 já traz o conversor oficial **`yamles`** (`posix/bin/yamles`,
  `yaml-entity-store-1.72.0.jar`). → **conversor retirado do escopo**; foco em execução e debug.

## Fase 2 — Engenharia reversa

1. **REA (MCP de engenharia reversa).** Útil para inventário de jars (evidência `ev_7c3aad…`), mas:
   - sem provedor de descompilação Java (JEB ausente);
   - Ghidra 12.1.4 foi instalado em `Z:\Tools` e registrado no REA (`GHIDRA_INSTALL_DIR`, `JAVA_HOME` em
     `~/.claude.json`, backup `Z:\Tools\claude.json.bak-*`), porém **no Windows o provedor Ghidra do REA só
     aceita PE**: recusou `.class` (magic `CAFEBABE` confundido com Mach-O FAT) e ELF (`libHTTP.so`).
2. **Vineflower 1.12.0** (`Z:\Tools\vineflower.jar`) descompilou os jars centrais para `re/src/<jar>/`
   (local, fora do git). `javap` para assinaturas.
3. **Achados-chave** (detalhes em [lifecycle-contract.md](lifecycle-contract.md)):
   - `InvocationEngine` público e estático; `MessageListener` recebe todos os eventos de execução →
     ganchos de debug **sem instrumentar bytecode**;
   - cadeia por path em `HTTPPlugin.configureCircuits`: Global Request → Custom Request → Path Specific →
     Custom Response → Global Response;
   - semântica: `false` interrompe a cadeia e chama fault handler; abort sem fault handler → last-chance /
     `system.policy.faulthandler`;
   - transporte HTTP é nativo (`libHTTP.so`), mas o despacho de policies é Java;
   - `CorrelationID` e `Trace` são JNI (`libvjni.so`).

## Fase 3 — Runtime nativo ("Modo A")

1. **Probe Jython:** `posix/bin/jython` (vshell + `jython.xml`) criou `CorrelationID` e `Message` reais no WSL
   → o runtime nativo funciona fora da VM. (`probes/native_probe.py`)
2. **Distribuição copiada para o ext4 do WSL:** boot do probe caiu de **20 s → 2,6 s** (o `/mnt/z` via 9p é lento).
3. **Instância completa não sobe:** licença do refenv expirou em 2025-01-24 ("NO PRODUCT LICENSE FOUND").
   → decisão de não depender da instância e **não contornar a licença** (D-04).
4. Pergunta de memória: probe usava ~280 MB; harness completo ~0,5 GB.

## Fase 4 — Bootstrap sem NetService

1. `Service` (NetService) faz: store → `registerCoreServices` → `ESSolutionPack` → `loadModules` → `loadCircuits`.
   `ServiceHelperImpl` depende do singleton `Service` → **`ApdServiceHelper`** própria.
2. Probe de bootstrap (`probes/bootstrap_probe.py`): store em 0,87 s, 206 policies, 56 tipos de módulo.
3. **Launcher do vshell:** o primeiro descritor com `<ClassPath>` não carregava a classe (e houve segfaults).
   Causa: `<ClassPath>` fora de `<JVMSettings>` é ignorado. Solução: `$VINSTDIR/ext/lib` (já previsto no
   `jvm.xml`) com `VINSTDIR` = pasta do apd.
4. **Segfaults geraram ~12 GB de dumps** em `%TEMP%\wsl-crashes` (C: cheio de novo). → `ulimit -c 0` no launcher.
5. **Módulos:** `NativeModule` (`SystemSettings`, `MimeTypeGroup`, `CORSGroup`) exige o objeto nativo pai
   ("null reference to native"). `PortalConfiguration` chama `LicenseService.checkPortalLicense()` → fora.
   Mais tarde, `TraceLogger.formatMsg` deu NPE porque `SystemSettings.getInstance()` era nulo → a metade Java
   (`SettingStore`) passou a ser populada a partir da entidade.

## Fase 5 — Ferramenta

Na ordem em que foram construídos e validados:

1. `GatewayRuntime`, `PolicyIndex`, `RequestSpec`, `Lifecycle`, `ExecutionTrace`, `Report`, `Main` →
   primeiro run real: `/healthcheck` 200 `<status>ok</status>`.
2. `DebugProcessor` (wrapper de filtros) + `--force` → abort forçado cai no `GenericFaultProcessor` (500 SOAP fault).
3. `/heroes` falhou no `ConnectToURLFilter`; **"Restricted Mode can not connect over plain sockets"** →
   `ServerLicenseChecker` exige a feature `unrestricted`. Primeira tentativa (`MockBackend` real + `/etc/hosts`
   privado via `unshare`) bloqueada pela licença → mantida como `--real-routing`.
4. **`VirtualBackend`**: intercepta só a transação, usa seletores do filtro e `Response.buildReply` real →
   `/heroes` 200 com JSON do mock.
5. `TestRunner` + exemplos: 8/8. Um "falha" revelou comportamento real: *Reflect Message* mascara um 503 do
   backend como 200 (teste documenta isso).
6. `Debugger` interativo validado com stdin roteirizado (breakpoint, `p`, `set`, `bt`, step, `attrs`, `force`).
7. `NetworkGuard` (bug corrigido: `Socket.connect` checa o **IP** → allowlist pré-resolve hostnames).
8. Modo `shell` (boot uma vez; `reload` ~0,7 s) e `doctor`.
9. Smoke test em todos os 63 paths da QuickStart: 0 erros da ferramenta (FAIL/ABORT esperados sem credenciais/mocks).
10. **Environment settings** (`EnvExpandEntityStore`, `--env-props`), **`.pol` + `.env`** via `DeploymentArchive`,
    `eval` no debugger (`${env.PORT.TRAFFIC}` → 8080).
11. Cache de extração por usuário (cache criado por root não era listável por outro usuário).

## Fase 6 — Produto

1. **Distribuição:** pacote sem nada da Axway + `setup.sh` que copia do runtime do usuário só
   `Linux.x86_64`, `platform`, `system`, `posix`, `conf` (~650 MB, contra 1,4 GB). Dockerfile opcional (não testado).
2. **Windows:** análise de 806 métodos nativos em 141 classes (OpenSSL 183, HTTP 148, dwe 113, libxml 88…) →
   rodar nativo no Windows exigiria reescrever o runtime nativo. Primeiro foi oferecido via WSL (`.cmd`);
   depois o usuário decidiu: **somente Linux** (D-10). `.cmd`/`.ps1` removidos; build/package em shell com JDK 17.
3. **Nome:** apigw-devkit → axway-policy-debugger → **apimgr-policy-debugger** ("apd"); pacote Java `apd`,
   variáveis `APD_*`.
4. **Análise de reescrita standalone (sem Axway):** 481 tipos de entidade, 235 filtros concretos; a QuickStart usa
   102 tipos (top 10 = 60%, top 20 = 76%, top 40 = 88% das instâncias). Blocos e ordem de grandeza em
   [roadmap.md](roadmap.md).
5. **Git:** repositório privado `JoaoAngelo/apimgr-policy-debugger`; `re/` e `runtime/` fora do git;
   `.gitattributes` LF; bits de execução definidos pelo git do WSL.

## Ambiente de desenvolvimento usado

| Item | Valor |
|---|---|
| Host | Windows 11, WSL2 Ubuntu 20.04 (distro movida para `Z:\WSL\Ubuntu-20.04`) |
| Projeto | `Z:\Projetos\apimgateway\apimgr-policy-debugger` (`/mnt/z/...` no WSL) |
| Runtime Axway | `/opt/axway/apigateway` no WSL (instalado por `bin/setup.sh` a partir de `extracted/opt-Axway.tar.gz`) |
| Config de exemplo | `runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58` (QuickStart, local, fora do git) |
| Ferramentas | Ghidra 12.1.4 e Vineflower 1.12.0 em `Z:\Tools`; JDK 17 no WSL; Zulu 25 no Windows |
