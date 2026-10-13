# Roadmap, lacunas conhecidas e ideias

> Estado na versão 1.0.0. Itens marcados com o que foi ou não verificado.

## 1. Lacunas conhecidas (não validadas / não implementadas)

| Item | Situação | Próximo passo |
|---|---|---|
| `HTTPClientFilter` (pre/post message creation, routing, post routing circuits) | não aparece na QuickStart; não validado | config com o filtro; confirmar se o processor é `ConnectionProcessor` (então já é coberto pelo `VirtualBackend`) |
| Websocket (incoming/outgoing policies) | não emulado | avaliar `WebsocketPlugin` |
| Connection Failure Policy no `VirtualBackend` | implementado por reflexão (`DelegatingProcessor.callCircuit`), sem caso de teste | criar config/caso com `runPolicyOnConnectionFailure` |
| `--real-routing` | implementado; bloqueado sem licença (`Restricted Mode`) | testar com licença válida (dev/eval) |
| Docker (`docker/build.sh`, `Dockerfile`) | escrito, **não testado** (sem Docker no host de dev) | `docker build` + rodar suíte |
| HTTPS em mocks (`--real-routing`) | `MockBackend` só aceita `http://` | servidor TLS local + trust |
| Runtime do API Manager (APIs virtualizadas, quotas) | fora (licença + Cassandra) | testar fases via `--chain` |
| `System.exit` vs `Runtime.halt` | não testado | ver architecture.md §5 |
| Filtros que dependem de módulos pulados | falham visivelmente; sem catálogo | mapear filtro → módulo exigido |
| `instanceof` de processor concreto vendo `DebugProcessor` | não observado | se surgir, permitir desligar o wrapper por tipo |

## 2. Melhorias sugeridas

1. **Comparação com o gateway real** (quando houver licença): rodar os mesmos casos contra uma instância e
   comparar status/headers/body/atributos.
2. **Cobertura de policies:** relatório de quais filtros/caminhos de cada policy foram exercitados pelos testes.
3. **Watch de atributos:** breakpoint condicional (`b attr:http.response.status`), `watch <attr>`.
4. **Gravação/replay:** salvar a `Message` de entrada/saída de um run como caso de teste YAML automaticamente.
5. **Debug Adapter Protocol** para breakpoints pela IDE (VS Code).
6. **Saída JUnit por filtro** e formato de relatório HTML.
7. **Hot reload no shell** ao detectar mudança no `.fed`.
8. **Catálogo de mocks** por policy (sugerir mocks a partir das URLs dos `ConnectToURLFilter`).

## 3. Estudo: reescrita standalone (sem nenhuma dependência Axway)

Levantamento feito para dimensionar (ver números em [axway-internals.md](axway-internals.md) §6):

| # | Bloco | Dificuldade |
|---|---|---|
| 1 | Loader `.fed/.pol/.env/YAML` + modelo de entidades (tipos vêm dentro do `.fed`) + env settings + campos cifrados | média |
| 2 | Linguagem de seletores `${...}` (env, system, kps, métodos, coerção) | **alta** |
| 3 | Motor (grafo, cadeia, fault handlers, shortcuts, classificação de abort) — semântica já documentada | baixa/média |
| 4 | Modelo HTTP/MIME (Body, multipart, HeaderSet) e, se runtime, listener (Netty) | média |
| 5 | Filtros em ondas por uso: onda 1 ≈ 40 tipos ≈ 88% das instâncias (QuickStart); ondas 2–3 XML/crypto/OAuth/SAML | escalonada |
| 6 | Scripts (JS/Jython/Groovy) que usam a API `com.vordel.*` | **alta** |
| 7 | Serviços: KPS, caches, cert/key stores, token/OAuth stores, SSL profiles | média/alta |
| 8 | Debug/teste (reaproveitar o design do apd) | baixa |

Ordem de grandeza estimada (não medida): MVP sandbox (1–4, 8, onda 1) ~2–3 devs × 6–9 meses; paridade ampla +1–2 anos.
O apd atual serve de **oráculo de teste diferencial** (mesmos casos YAML nos dois motores, comparar traces).
Primeiro passo recomendado: rodar o levantamento de tipos × uso nos `.fed` reais dos clientes.

## 4. Porte para Windows nativo (descartado — D-10)

Exigiria reimplementar JNI: núcleo (~140 métodos) para policies simples; OpenSSL (~180) e libxml (~88) para
crypto/XML. Mantido apenas como registro.
