# Segurança (catalog-backend)

Factos estáveis da configuração de segurança (TASK-002, 2026-10-06). Resource server JWT do
Auth0, uma só `SecurityFilterChain` em `SecurityConfig`. Cada ponto abaixo foi medido; a prova
está no teste indicado.

## Lista pública: `PublicEndpoints.MATCHER` é a fonte única

- Sem token: `GET`/`HEAD` de `/api/cars`, `/api/cars/{id}`, `/api/cars/highlights`;
  `POST /api/leads`; `/actuator/health`, `/actuator/health/**`, `/actuator/info`. Tudo o resto
  exige JWT (`anyRequest().authenticated()`); perfis por `@PreAuthorize` nos controllers, nunca
  regras por caminho no `SecurityConfig`.
- `/api/**` sempre com método explícito. `HEAD` está na lista porque o Spring MVC serve `HEAD`
  pelos handlers de `GET` (sem ele passava de 200 a 401).
- **Armadilha para o back-office**: um `GET /api/cars/<um segmento>` novo (ex.
  `/api/cars/stats`) fica público por cair em `/api/cars/{id}`. `PublicSurfaceTest` percorre
  todos os mapeamentos do `RequestMappingHandlerMapping` e falha se o conjunto público mudar.
  Rotas do back-office vivem noutro espaço de caminhos; nunca `POST /api/leads`. Esse espaço é
  `/api/backoffice` (TASK-003), guardado por `BackOfficeSurfaceTest`: ver `../web/CLAUDE.md`.

## Porque é que o despacho `ERROR` é `permitAll()`

O Spring Security autoriza todos os tipos de despacho. Erros produzidos com `sendError` (o 406
de `POST /api/leads` com `Accept: application/xml`, os 400 da `StrictHttpFirewall`) são
renderizados num segundo despacho, `ERROR`, para `/error`; sem a regra, viravam 401. Só o
despacho `ERROR` é aberto: `GET /error` pedido diretamente é `REQUEST` e dá 401.

**O MockMvc não faz esse despacho.** Qualquer teste sobre status de erro que dependa de
`/error` tem de correr em porta real (`RANDOM_PORT`, ver `web/PublicEndpointsHttpTest`).

## Token ignorado nos públicos

O `BearerTokenResolver` devolve `null` quando `PublicEndpoints.MATCHER` aceita o pedido: o
site nunca chama o decoder (não depende do Auth0), e um token expirado ou de um utilizador
inativo não muda nada nos públicos. Consequência: nos públicos não há `JwtAuthenticationToken`
e o `AuthenticatedUserProvider` devolve vazio. O resolver não é um bean de propósito (um bean
seria apanhado implicitamente pelo DSL e tirar a linha explícita não se notava).

## `AUTH0_*` e o `JwtDecoder`

- O `JwtDecoder` é declarado em `SecurityConfig` a partir de `app.auth0.*`; não há
  `spring.security.oauth2.resourceserver.*` no `application.yml`.
- `AUTH0_ISSUER_URI` ou `AUTH0_AUDIENCE` vazio: o decoder lança `BadJwtException("Token
  invalido")` para qualquer token (401), sem rede, e há um `WARN` no arranque. A mensagem vai
  no `WWW-Authenticate`, por isso nunca diz que falta configuração.
- **`AUTH0_ISSUER_URI` malformado nunca lança** (review r2: lançava na criação do bean e a
  aplicação, site público incluído, não arrancava). `Auth0Issuer` só aceita a forma exata que o
  Auth0 põe no `iss`: `https://<dominio>/`, minúsculas, **com a barra final**, sem porta, caminho,
  query, fragmento nem credenciais (`http` só para `localhost`/`127.0.0.1`/`[::1]`, o `FakeAuth0`
  dos testes). Qualquer outro valor dá o mesmo decoder que recusa tudo (401, zero rede) e **um**
  `WARN` no arranque com o valor (entre aspas, só ASCII imprimível, cortado a 100 caracteres), o
  problema e o formato esperado. O valor nunca é normalizado: a comparação com `iss` é exata
  (`String.equals`), por isso sem a barra final todos os tokens dariam 401 em silêncio; é melhor
  recusar com aviso. O "Domain" do painel do Auth0 (`oteustand.eu.auth0.com`) **não** serve tal
  como está. Prova: `Auth0IssuerTest` (60+ valores), `MalformedAuth0IssuerTest` (decoder, aviso,
  zero pedidos ao tenant), `MalformedAuth0IssuerContextTest` (aplicação inteira arranca).
- **Nunca um issuer placeholder** (o `https://placeholder.auth0.com/` do `dcbo-backend`): um
  tenant Auth0 é de quem o registar primeiro, e esse terceiro passava a emitir tokens aceites;
  um tenant inexistente prendia cada pedido à espera de rede (o arquiteto mediu um `curl` a
  esgotar 10 s em 2026-10-04).
- Com os dois preenchidos: `NimbusJwtDecoder.withJwkSource(Auth0JwkSource)`, só RS256 (`none` e
  HMAC recusados antes de procurar chave), validador `iss` + tempo + `aud` explícito
  (`SecurityConfig.validator`). **Sem descoberta OIDC**: o JWKS vem de
  `<issuer>.well-known/jwks.json`, onde o Auth0 o publica sempre; uma chamada ao Auth0 em vez de
  duas, nenhuma no arranque.
- `typ` do token tem de ser `JWT` ou ausente (`JwtValidators` do Spring Security 7). O Auth0 emite
  `typ: JWT` por omissão; se o perfil de token da API for mudado para RFC 9068 (`at+jwt`), todos
  os tokens passam a 401.
- **Auth0 inalcançável com config real = 500, deliberado.** Não é 401 de propósito (o token pode
  ser válido e o `dcbo` terminaria a sessão). O 500 sai por `sendError` no failure handler do
  filtro de bearer (`SecurityConfig.bearerAuthenticationFailureHandler`), com o corpo de erro do
  Boot e uma linha `DEBUG`. O handler por omissão do Spring relança a exceção e o Tomcat escreve
  uma stack trace `ERROR` por pedido (medido na review r1: 90 MB de log em 20 s).
- `AUTH0_ROLES_CLAIM` tem default de produção `https://oteustand.pt/roles`. Uma variável de
  ambiente **definida e vazia não cai no default** do `${VAR:default}` (dá `""`, medido): zero
  roles para todos. Por isso o `.env.example` traz o valor e não a linha vazia.

## Auth0 lento ou em baixo: `Auth0JwkSource`

Medido na review r1 (2026-10-06) com o `SupplierJwtDecoder` + `withIssuerLocation`: com o Auth0
mudo, 250 pedidos anónimos com `Bearer x.y.z` puseram `GET /api/cars` em timeout de 30 s (a
descoberta OIDC é serializada, 3 s por pedido, e a falha não fica guardada); com o JWKS pendurado e
`kid` inventados, públicos e admin a 14,5 s (o `SpringJWKSource` faz lock em cada lookup e vai
buscar o JWKS de novo a cada `kid` desconhecido). O `CachingJWKSetSource` do Nimbus também não
serve: deixa esperar um número ilimitado de pedidos e responde 500 a um `kid` desconhecido
limitado por rate limit. Regras do `Auth0JwkSource`, cada uma com teste em `Auth0JwkSourceTest`:

- `kid` conhecido: memória, sem lock, sem rede, mesmo com o Auth0 em baixo (as últimas chaves boas
  ficam até um fetch ter sucesso).
- Sem chaves ou `kid` desconhecido: um só pedido vai ao Auth0, no máximo 8 esperam por ele (até
  6 s), os restantes respondem logo. Nunca mais de 9 threads do Tomcat presas pelo Auth0, e nunca
  mais de 6 s cada, seja qual for o número de pedidos.
- O fetch (`DeadlineResourceRetriever`, `HttpClient` do JDK): ligação e cabeçalhos em 3 s
  (`AUTH0_TIMEOUT`), resposta completa em 6 s, corpo até 50 KB contado enquanto chega, só `2xx`,
  sem seguir redirecionamentos. **Não usar `HttpURLConnection`** (o `DefaultResourceRetriever` do
  Nimbus) para isto: o read timeout é por leitura (um JWKS a 1 byte cada 2 s prendeu o fetch mais
  de 25 s, review r2), e `disconnect()` noutra thread não o corta enquanto os cabeçalhos chegam (no
  JDK 21 espera pelo mesmo lock que a thread que lê; medido). O `timeout` do `HttpRequest` inclui a
  ligação (medido: endereço não encaminhável falha ao fim do timeout) e o `cancel(true)` do
  `sendAsync` fecha a ligação (o servidor vê o reset na escrita seguinte).
- Intervalo mínimo de 10 s entre tentativas (contado do fim da anterior): é a cache negativa depois
  de uma falha e o limite para `kid` inventados (no máximo um fetch por intervalo). Quando o Auth0
  volta, o primeiro pedido depois do intervalo vai buscar as chaves: no pior caso **até ~16 s**
  depois (uma tentativa em curso pode durar até 6 s, e o intervalo conta do fim dela; com o Auth0
  mudo a tentativa acaba aos 3 s, ~13 s; review r2 mediu 13-16 s).
- Chaves com mais de 5 min: renovadas numa virtual thread, o pedido não espera. Uma chave revogada
  no Auth0 deixa de ser aceite até 5 min depois.
- Resposta: chave encontrada; nenhuma (401) se as chaves são atuais; `KeySourceException` (500) se
  não há chaves ou a última tentativa falhou (não é possível confirmar o `kid`).
- Log: uma linha `WARN` por fetch falhado (no máximo uma por intervalo), uma `INFO` quando volta.
- Um token que nem é JWT (`x.y.z`) é 401 sem chegar ao Auth0.
- Medição (2026-10-07, jar em processo real, 250 clientes durante 20 s, numa máquina de 8 núcleos
  que corre também o gerador de carga): o "antes" e o "depois" estão nas `## Notas` da TASK-002.
  Com 5000 pedidos/s o próprio gerador satura o CPU: a cauda de ~1 s nos públicos aparece igual
  num controlo sem `AUTH0_*` (401 imediato), não vem do Auth0.

## Erros 401/403

- 401: `ApiErrorAuthenticationEntryPoint` chama primeiro o `BearerTokenAuthenticationEntryPoint`
  (status e `WWW-Authenticate: Bearer ...`) e escreve `ApiError` com mensagem fixa
  `Autenticacao necessaria` usando o `JsonMapper` da aplicação (mesmo formato de `timestamp`
  que o `ApiExceptionHandler`). O Spring Security 7 acrescenta `resource_metadata=` (RFC 9728)
  ao `WWW-Authenticate`, a apontar para uma rota que não existe; inofensivo.
- 403 conta desativada: `ActiveUserInterceptor` (primeiro interceptor do `WebConfig`) lança
  `InactiveUserException` no `preHandle`, antes do `@PreAuthorize`. Sem linha em `app_users`
  não recusa (a tabela começa vazia).
- 403 sem role: o handler de `AccessDeniedException` no `ApiExceptionHandler` é obrigatório;
  sem ele o `@ExceptionHandler(Exception.class)` transformava cada recusa em 500.

## CORS

`CorsConfig` só declara o `CorsConfigurationSource` (em `/api/**`, origens de
`CORS_ALLOWED_ORIGINS`, sem `*`, sem credenciais, `maxAge` 1800 s); não há `addCorsMappings`.
O `CorsFilter` da cadeia responde ao preflight antes da autenticação.

**Armadilha medida**: no Spring Security 7.1.1, `HttpSecurityConfiguration#applyCorsIfAvailable`
liga `cors(withDefaults())` sozinho sempre que existe um bean `UrlBasedCorsConfigurationSource`.
Tirar a linha `.cors(...)` do `SecurityConfig` não muda nada (mutante equivalente); a mutação
que desliga mesmo o CORS é `.cors(AbstractHttpConfigurer::disable)`, e os testes de preflight
apanham-na. A linha explícita fica: o `withDefaults()` procura o bean pelo nome
`corsConfigurationSource` (`CorsConfigurer`), e a linha explícita liga a fonte certa mesmo que
o bean mude de nome ou apareça um segundo.

## Testes

- `@SpringBootTest` sem `AUTH0_*` (o default) nunca faz rede. Testes que precisem de um issuer
  usam `config/support/FakeAuth0` (tenant em processo: JWKS com chaves RSA geradas, tokens RS256,
  `hang()`, `drip()`, `publishJson()`), uma porta local fechada ou um `ServerSocket` que nunca
  responde, nunca um host real: um host real esconde mutantes (o decode vai à rede e falha por outro
  motivo).
- O `FakeAuth0` serve também a descoberta OIDC (e pendura-a como o JWKS) e conta pedidos de
  qualquer tipo (`requests()`): sem isso, um decoder que voltasse à descoberta recebia 404 depressa
  e o teste de Auth0 pendurado falhava por outro motivo (review r2, S-d). Para "zero chamadas ao
  Auth0", afirmar `requests()`, não `jwksRequests()`.
- **Limites de segurança afirmados com literais** (10 s, 5 min, 8, 50 KB, 3 s/6 s), dos dois lados
  da fronteira, sobre a fonte de produção (`Auth0JwkSource.forIssuer` com relógio falso). Os
  limites são `private` de propósito: um teste que avançava o relógio pela própria constante
  continuava verde com uma vida de chaves de 1000 dias (review r2, S-e).
- O decoder de produção só é provado por `Auth0JwtDecoderTest` (tokens RS256 do `FakeAuth0`): o
  decoder HMAC de teste dos outros testes tem o seu próprio validador de `aud`, e por isso apagar
  `setJwtValidator` do `SecurityConfig` passava-lhes ao lado (review r1).
- Rajadas de pedidos em porta real (`PublicEndpointsAuth0HungHttpTest`): 250 `connect` simultâneos
  excedem o backlog do Tomcat (`acceptCount` 100) e dão `ConnectException` no cliente (medido no
  Windows); o teste espaça-os 2 ms.
- Testes que podem pendurar (rede) usam `assertTimeoutPreemptively`: sem os limites do fetch,
  falham com mensagem em vez de pendurar a suite.
- `OutputCaptureExtension` num `@SpringBootTest` com propriedades só dessa classe apanha também o
  log do arranque do contexto (é criado depois do `beforeAll` da captura): é assim que
  `MalformedAuth0IssuerContextTest` prova "um só `WARN`, no arranque". Com um contexto em cache,
  partilhado com outra classe, o arranque não aparece.
- `JwtDecoder` de teste = `@Bean @Primary` com outro nome (`TestJwtDecoderConfig`). O Spring
  Boot 4 desliga a sobreposição de beans: o mesmo nome dá `BeanDefinitionOverrideException`.
- Spring Security 7 acrescenta sempre a authority `FACTOR_BEARER` a uma autenticação por
  bearer token: "sem roles" afirma-se como "nenhuma authority começa por `ROLE_`", nunca
  "lista vazia".
- Controller de sonda só em `src/test`, aninhado num `@TestConfiguration`
  (`config/support/SecurityProbeConfig`): o component scan ignora-o, e o `@Import` regista a
  classe aninhada sozinho. Declará-lo também como `@Bean` mapeia as rotas duas vezes
  (`Ambiguous mapping`).
- Com dois campos inválidos em `POST /api/leads`, a ordem das mensagens no `message` muda entre
  execuções (já antes da segurança, medido pelo arquiteto em 2026-10-04): testes de 400 usam um
  campo inválido só.
