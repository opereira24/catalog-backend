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
  Rotas do back-office vivem noutro espaço de caminhos; nunca `POST /api/leads`.

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
- **Nunca um issuer placeholder** (o `https://placeholder.auth0.com/` do `dcbo-backend`): um
  tenant Auth0 é de quem o registar primeiro, e esse terceiro passava a emitir tokens aceites;
  um tenant inexistente prendia cada pedido à espera de rede (o arquiteto mediu um `curl` a
  esgotar 10 s em 2026-10-04).
- Com os dois preenchidos: `SupplierJwtDecoder` (sem rede no arranque), `RestTemplate` com 3 s
  de connect e read timeout (o default não tem nenhum), validador `iss` + tempo + `aud`
  explícito (`SecurityConfig.validator`).
- **Auth0 inalcançável com config real = 500, deliberado.** A descoberta OIDC falha com
  `JwtDecoderInitializationException`, que não é erro de autenticação: o pedido acaba no corpo
  de erro do Boot. Não é 401 de propósito (o token pode ser válido e o `dcbo` terminaria a
  sessão). Tenta de novo no pedido seguinte. Os públicos não são afetados.
- `AUTH0_ROLES_CLAIM` tem default de produção `https://oteustand.pt/roles`. Uma variável de
  ambiente **definida e vazia não cai no default** do `${VAR:default}` (dá `""`, medido): zero
  roles para todos. Por isso o `.env.example` traz o valor e não a linha vazia.

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
  usam uma porta local fechada ou um `ServerSocket` que nunca responde, nunca um host real: um
  host real esconde mutantes (o decode vai à rede e falha por outro motivo).
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
