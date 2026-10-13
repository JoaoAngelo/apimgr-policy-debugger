# Operação, build, verificação e problemas conhecidos

## 1. Pré-requisitos

| Item | Detalhe |
|---|---|
| SO | Linux x86_64 (testado: Ubuntu 20.04 em WSL2) |
| Runtime Axway | instalação API Gateway 7.7 (dir `apigateway` ou `.tar.gz`), instalada por `bin/setup.sh` |
| Build | JDK ≥ 11 (`javac`, `jar`) — ex.: `apt-get install openjdk-17-jdk-headless`; compila com `--release 11` |
| Disco | ~650 MB para o runtime; ~5 MB para o apd |
| Memória | ~0,5 GB por processo (`APD_HEAP`, padrão 1024m) |
| Root | só para `setup.sh` em `/opt` e para `--real-routing` (mount namespace) |

## 2. Fluxo do zero (clone)

```sh
git clone https://github.com/JoaoAngelo/apimgr-policy-debugger.git && cd apimgr-policy-debugger
sudo sh bin/setup.sh /caminho/opt-Axway.tar.gz        # ou a pasta .../apigateway; grava conf/runtime.env
sh build.sh                                           # ext/lib/apimgr-policy-debugger.jar
bin/apimgr-policy-debugger --version
bin/apimgr-policy-debugger doctor --config /opt/axway/apigateway/system/conf/templates/FactoryConfiguration-VordelGateway.fed
```

Em clone novo sem `conf/runtime.env`, o apd usa `/opt/axway/apigateway` (ou `AXWAY_HOME`).

## 3. Variáveis de ambiente

| Variável | Uso |
|---|---|
| `AXWAY_HOME` | runtime Axway (sobrescreve `conf/runtime.env`) |
| `APD_HEAP` | heap máximo (ex.: `2g`) |
| `APD_CONFIG`, `APD_ENV`, `APD_PASSPHRASE` | padrões para `--config`, `--env`, `--passphrase` |
| `APD_HOME`, `APD_NS`, `APD_HOSTS_FILE` | internas do launcher |
| `NO_COLOR` | desliga cores |

## 4. Verificação (rodar antes de commitar mudanças)

```sh
sh build.sh
CFG=<grupo QuickStart>/conf/<config-id>      # no ambiente de dev: runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58
bin/apimgr-policy-debugger test examples/quickstart/tests --config "$CFG"        # esperado: 8 passed
bin/apimgr-policy-debugger doctor --config "$CFG"                                 # esperado: 0 failed, isolated
sh probes/fresh_clone_check.sh "$CFG"                                             # clone limpo: build + testes
```

Smoke de todos os paths (esperado: nenhuma linha `error:`; FAIL/ABORT são comportamento das policies):
```sh
bin/apimgr-policy-debugger paths --config "$CFG"   # gere comandos `run --service … --path … --url … --quiet --no-diff`
# e passe-os para: bin/apimgr-policy-debugger shell --config "$CFG"
```

Pacote: `sh package.sh` → `dist/apimgr-policy-debugger-<versão>.tar.gz`. Versão em `src/apd/Main.java` (`VERSION`).

## 5. Releases (GitHub Actions)

Workflow [`.github/workflows/release.yml`](../.github/workflows/release.yml):

| Gatilho | Resultado |
|---|---|
| push de tag `vX.Y.Z` | compila, empacota e publica a release `vX.Y.Z` com `apimgr-policy-debugger-X.Y.Z.tar.gz`, `.zip` e `SHA256SUMS` (notas geradas dos commits) |
| `workflow_dispatch` (manual) | só compila e empacota; pacotes ficam como *artifact* da execução |

- A tag precisa ser igual a `v` + `VERSION` de `src/apd/Main.java` (o job falha se divergir).
- **Cortar uma versão:** `sh tools/release.sh 1.1.0` (ajusta `VERSION`, commita, cria a tag e dá push).
- **Dependência de compilação:** o runner não tem a Axway. Os 24 jars necessários para compilar (calculados por
  `tools/collect-build-libs.sh`, ~10 MB) ficam no asset `apd-build-libs.tar.gz` da pre-release técnica
  `build-deps` deste repositório. Ao mudar imports ou atualizar a versão da Axway, regenere e substitua:
  ```sh
  sh tools/collect-build-libs.sh                       # gera dist/apd-build-libs.tar.gz a partir de AXWAY_HOME
  gh release upload build-deps dist/apd-build-libs.tar.gz --clobber
  ```
- O CI não executa os testes (precisariam do runtime completo com JNI e de uma configuração). Reproduza o CI
  localmente e rode os testes do pacote gerado com `sh probes/ci_local_check.sh`.

## 6. Git

- Opere o git **de dentro do Linux/WSL**: o repositório tem `core.fileMode=false`, `core.autocrlf=false` e
  `.gitattributes` com `eol=lf`. Scripts executáveis: `bin/apimgr-policy-debugger`, `bin/setup.sh`, `build.sh`,
  `package.sh`, `docker/build.sh` (`git update-index --chmod=+x <arquivo>` para novos).
- Fora do git: `re/` (descompilado), `runtime/` (config QuickStart), `build/`, `dist/`, `ext/lib/*.jar`,
  `conf/runtime.env`, `conf/kps/`, `conf/tmp/`.

## 7. Problemas conhecidos e soluções

| Sintoma | Causa | Solução |
|---|---|---|
| Disco C: zera ao usar o apd no WSL | core dumps do vshell (`posix/lib/venv` faz `ulimit -c unlimited`) vão para `%TEMP%\wsl-crashes` (GBs cada) | launcher faz `ulimit -c 0`; manter isso em qualquer script que chame o vshell |
| vshell sai sem saída / segfault ao testar um descritor | `<ClassPath>` fora de `<JVMSettings>` é ignorado → main não encontrada | usar `$VINSTDIR/ext/lib` (D-07) |
| `Unrecognised configuration … .fed` com usuário diferente | cache de extração criado por outro usuário (permissões) | cache por usuário: `$TMP/apimgr-policy-debugger-<user>/archives` |
| `NullPointerException` em `TraceLogger.formatMsg` | `SystemSettings.getInstance()` nulo | `GatewayRuntime.loadSettingStore` popula o `SettingStore` |
| `Field [circuitPK], ESPK cannot be FSPK` | `CircuitReference` com PK de arquivo | `PortableESPK.toPortableKey(store, pk)` |
| policy de path aparece `(none)` | referência entre stores federadas | `PolicyIndex.byPk` resolve via `store.getEntity(pk).getPK()` |
| `--allow-network host` não libera | `Socket.connect` verifica IP | allowlist pré-resolve hostnames (`NetworkGuard`) |
| "Restricted Mode can not connect over plain sockets" | cliente HTTP nativo sem licença `unrestricted` | não usar `--real-routing`; mocks no `VirtualBackend` |
| módulo `native` em `info` | `NativeModule` precisa do NetService | esperado para MimeTypeGroup/CORSGroup |
| scripts com `\r` ("not found", `$'\r'`) | edição no Windows com CRLF | `.gitattributes` LF; `sed -i 's/\r$//' arquivo` |
| boot lento (~20 s) | runtime em `/mnt/<drive>` (9p) | runtime no filesystem Linux (`/opt/axway/apigateway`) |
| Git Bash no Windows altera `/mnt/...` em comandos `wsl` | conversão de path do MSYS | `MSYS_NO_PATHCONV=1` ou rodar direto no WSL |

## 8. Probes (investigação)

| Script | Para quê |
|---|---|
| `probes/native_probe.py` | prova JNI/runtime: cria `CorrelationID` e `Message` via `posix/bin/jython` |
| `probes/bootstrap_probe.py` + `probes/run_probe.sh` | carrega uma config e lista módulos/policies (Jython) |
| `probes/start_instance.sh` | tentativa de subir a instância completa (falha por licença; histórico) |
| `probes/linux_e2e.sh` | build + package + instalação limpa + doctor + testes |
| `probes/fresh_clone_check.sh` | clone limpo do repo + build + testes |
