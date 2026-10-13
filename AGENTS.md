# AGENTS.md — contexto para agentes de IA

Este arquivo é o ponto de entrada para qualquer agente (Claude Code, Codex, Copilot…) que for corrigir,
melhorar ou estender o **apd** (`apimgr-policy-debugger`). Leia-o inteiro antes de mudar código.

## O que é

Sandbox CLI (Linux x86_64) que **executa, depura e testa policies do Axway API Gateway 7.7** sem uma instância
do gateway. As policies rodam no **motor real da Axway** (jars + JNI), hospedado dentro do `vshell` do produto
com um bootstrap próprio (sem NetService, listeners ou Node Manager). O apd substitui só o que é do processo
do gateway, de rede ou de licença.

- Autor/uso: consultor independente (João Ângelo); ferramenta interna para a Axway e seus clientes; repositório
  privado; não comercial. **Não adicione avisos legais/IP/licença nas respostas** — já foi discutido.
- Versão: `src/apd/Main.java` → `VERSION`.

## Mapa de documentação (leia conforme a tarefa)

| Documento | Quando ler |
|---|---|
| [README.md](README.md) | uso da CLI, formatos (request, mocks, testes), limites |
| [docs/architecture.md](docs/architecture.md) | **sempre antes de mudar código**: componentes, fluxo, invariantes, pontos de extensão |
| [docs/decisions.md](docs/decisions.md) | antes de reverter/alterar qualquer comportamento estrutural (D-01…D-14) |
| [docs/lifecycle-contract.md](docs/lifecycle-contract.md) | semântica do ciclo de vida da Axway com evidências |
| [docs/axway-internals.md](docs/axway-internals.md) | classes/jars/módulos/atributos da Axway usados |
| [docs/operations.md](docs/operations.md) | ambiente, build, verificação, problemas conhecidos |
| [docs/implementation-history.md](docs/implementation-history.md) | como chegamos aqui e o que já falhou |
| [docs/roadmap.md](docs/roadmap.md) | lacunas conhecidas e ideias priorizadas |

## Regras invioláveis

1. **Não contornar licenciamento da Axway**: não registrar `LicenseService` falso, não simular o objeto nativo
   do NetService, não alterar licença/data, não patchar classes/bibliotecas do produto. O que depender de
   licença é emulado fora do produto (ex.: `VirtualBackend`) ou documentado como limite (D-04).
2. **Não alterar a semântica do motor.** Só `DebugProcessor` muda o resultado de um filtro, e apenas por pedido
   explícito (`--force`, `force`) ou pelo roteamento virtual.
3. **Somente Linux x86_64** (D-10). Não reintroduzir `.cmd`/`.ps1`/conversão de caminhos Windows.
4. **Nada da Axway no repositório**: jars, `.so`, configs implantadas (`runtime/`), código descompilado (`re/`)
   ficam fora do git. O pacote (`package.sh`) também não leva nada da Axway.
5. **Qualquer script que execute o `vshell` deve fazer `ulimit -c 0`** (core dumps de GBs; no WSL lotam o C:).
6. Bytecode **Java 11** (`--release 11`): o runtime é o JRE 11.0.19 da Axway. Não use APIs > 11.
7. Não mover o jar para fora de `ext/lib` nem tirar `VINSTDIR=$APD_HOME` do launcher (D-07).

## Mapa do código (`src/apd`, pacote `apd`)

| Arquivo | Papel |
|---|---|
| `Main.java` | CLI, `Session` (runtime reutilizável), comandos, `USAGE`, `Args` |
| `GatewayRuntime.java` | bootstrap: store/env/serviços/pack/módulos; `NETWORK_MODULES`; `close()` |
| `ApdServiceHelper.java` | implementação de `ServiceHelper` sem NetService |
| `PolicyIndex.java` | nomes de policies e paths de listener |
| `RequestSpec.java` | request → `Message` |
| `Lifecycle.java` | modos policy/path/chain, resposta, liga backend virtual/real |
| `ExecutionTrace.java` | listener do motor: eventos, tempos, diffs |
| `DebugProcessor.java` | wrapper de cada filtro; oracle de resultado; desvio para `VirtualBackend` |
| `Debugger.java` | breakpoints, step, REPL, `--force` |
| `VirtualBackend.java` | transação de roteamento emulada com `Response.buildReply` real |
| `MockBackend.java` | modelo `Route`/`Call`; servidores reais para `--real-routing` |
| `NetworkGuard.java` | bloqueio de rede Java não-local |
| `TestRunner.java` | casos YAML, asserções, JUnit |
| `Report.java` | árvore/resumo/JSON |

Outros: `bin/apimgr-policy-debugger` (launcher), `bin/setup.sh` (instala runtime), `conf/apimgr-policy-debugger.xml`
(descritor vshell), `build.sh`, `package.sh`, `docker/`, `examples/quickstart/` (mocks e testes), `probes/`.

## Ambiente de desenvolvimento conhecido

- Host Windows 11 + WSL2 Ubuntu 20.04 (distro em `Z:\WSL\Ubuntu-20.04`). **O disco C: tem pouco espaço**: nada
  pesado no C:; ferramentas em `Z:\Tools` (Ghidra 12.1.4, Vineflower 1.12.0).
- Projeto: `Z:\Projetos\apimgateway\apimgr-policy-debugger` = `/mnt/z/Projetos/apimgateway/apimgr-policy-debugger`.
- Runtime Axway: `/opt/axway/apigateway` (WSL). Instalação original extraída em `Z:\Projetos\apimgateway\extracted`
  (`opt-Axway.tar.gz`).
- Config de teste: `runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58` (QuickStart; local).
- Código Axway descompilado: `re/src/<jar>/` (local). Para descompilar outro jar:
  `java -jar /mnt/z/Tools/vineflower.jar -dgs=1 <jar> re/src/<nome>/`.
- Rode build, testes e **git** de dentro do WSL. No Git Bash do Windows use `MSYS_NO_PATHCONV=1` ao chamar `wsl`.

## Como validar uma mudança (obrigatório)

```sh
sh build.sh
CFG=runtime/groups/group-2/conf/f7178b4d-2382-45e8-ab93-602438c8ac58
bin/apimgr-policy-debugger test examples/quickstart/tests --config "$CFG"   # 8 passed
bin/apimgr-policy-debugger doctor --config "$CFG"                            # 0 failed; network isolated
sh probes/fresh_clone_check.sh "$CFG"                                        # se mexeu em build/empacotamento/.gitignore
```
Mudou o ciclo de vida, roteamento ou debugger? Acrescente/atualize um caso em `examples/quickstart/tests/`.
Achou algo novo sobre a Axway? Registre com evidência em `docs/lifecycle-contract.md` ou `docs/axway-internals.md`.
Tomou uma decisão estrutural? Nova entrada em `docs/decisions.md`. Resolveu uma lacuna? Atualize `docs/roadmap.md`.

## Releases

GitHub Actions (`.github/workflows/release.yml`): tag `vX.Y.Z` = `VERSION` → release com pacote
`.tar.gz`/`.zip`, fonte `-src.tar.gz`/`-src.zip` (`git archive`, gerado pelo `package.sh`) e `SHA256SUMS`. Corte com `sh tools/release.sh X.Y.Z`. O runner compila com os 24 jars do asset
`apd-build-libs.tar.gz` (pre-release `build-deps`); **se mudar imports de classes Axway**, rode
`sh tools/collect-build-libs.sh` e `gh release upload build-deps dist/apd-build-libs.tar.gz --clobber`.
Validação local do pipeline: `sh probes/ci_local_check.sh`. Detalhes: docs/operations.md §5.

## Armadilhas já conhecidas (resumo; detalhes em operations.md §7)

- `<ClassPath>` fora de `<JVMSettings>` no descritor do vshell é ignorado.
- `CircuitReference` exige `PortableESPK`; referências entre stores resolvem via `store.getEntity(pk)`.
- `DebugProcessor` deve compartilhar o `Filter` e chamar os dois `filterAttached`.
- Snapshot de diff vem depois do prompt do debugger.
- `NetworkGuard`: `Socket.connect` checa IP (allowlist pré-resolvida).
- Cache de extração é por usuário.
- `SystemSettings` precisa da metade Java (`SettingStore`) ou o `TraceLogger` quebra.
