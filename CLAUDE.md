# catalog-backend

O `catalog-backend` é o **único** backend do diamondcars (catálogo público do `dc` + API do back-office do `dcbo`); o `dcbo-backend` está a ser retirado.

Spring Boot 4.1.1, Java 21, Maven (`./mvnw`), PostgreSQL (Neon em produção, Testcontainers em
teste), deploy Docker no Render. Factos por pasta, cada um com o teste que o prova:

- `src/main/java/pt/diamondcars/catalogbackend/config/CLAUDE.md`: segurança (JWT do Auth0, lista
  pública, 401/403, CORS, `AUTH0_*`).
- `src/main/java/pt/diamondcars/catalogbackend/domain/CLAUDE.md`: modelo de dados, migrações
  Flyway, ADR-001 nas colunas.
- `src/main/java/pt/diamondcars/catalogbackend/web/CLAUDE.md`: API do back-office (`/api/backoffice`)
  e o **inventário de endpoints**.

A convenção de nomes PT/EN (ADR-001) é cross-repo e vive em
`ai-structure/projects/diamondcars/project.md`; não se repete aqui.

## Build, testes e ferramentas

Factos técnicos herdados do `dcbo-backend` (mesma base técnica), ainda válidos aqui:

- `./mvnw verify` corre a suite toda (unitários e integração, todos pelo Surefire). Sem lint
  configurado (nem Checkstyle nem Spotless): não inventar um comando de lint.
- **Docker Desktop tem de estar a correr antes de `./mvnw verify`** (Testcontainers). Nesta máquina
  nem sempre está: `docker version` falha com erro de pipe se estiver parado; arrancá-lo e esperar
  ~35-40 s antes de repetir.
- **Diagnostics do IDE em `.java` ficam muitas vezes stale logo a seguir a uma edição** (erros de
  sintaxe falsos). Confirmar com `./mvnw -o -q compile` antes de reagir.
- **Testcontainers 2.0.5** (o do BOM do Boot 4.1.1): artefactos `testcontainers-junit-jupiter` e
  `testcontainers-postgresql` (os nomes antigos `junit-jupiter`/`postgresql` não existem nessa
  versão) e a classe `org.testcontainers.postgresql.PostgreSQLContainer` (não genérica; a antiga
  `org.testcontainers.containers.PostgreSQLContainer<SELF>` está `@Deprecated`). Auditar contra o
  `.pom` do BOM em `~/.m2`, nunca contra documentação.
- **Um só contentor PostgreSQL por JVM**: campo `static final` com `@ServiceConnection` em
  `support/AbstractPostgresIntegrationTest`, arrancado num bloco estático, **sem**
  `@Testcontainers`/`@Container` (essas anotações são por classe). Prova de que é singleton: no log
  da corrida, `grep -c "Creating container for image: postgres"` = 1. Orçamento de ligações e
  contextos Spring em cache: `web/CLAUDE.md`, "Testar corridas de forma determinística".
- `gh pr review --approve` falha nos PRs deste repo: o autor e o reviewer são a mesma conta
  (`opereira24`) e o GitHub recusa auto-aprovação. Usar `--comment` ou `--request-changes`.

## Variáveis de ambiente e deploy no Render

A lista completa está em `.env.example`, e `config/EnvExampleTest` falha se ela divergir das
variáveis que o `application.yml` lê. Nenhum agente toca no Render nem no Auth0: o que está abaixo
é a lista do que o Gonçalo configura à mão.

| Variável | Obrigatória? | Valor de produção | Se faltar ou estiver errada | Prova |
|---|---|---|---|---|
| `DB_URL` | sim | `jdbc:postgresql://<host>.neon.tech/<database>?sslmode=require` (começa por `jdbc:`) | sem `jdbc:`: a aplicação **não arranca** (`'url' must start with "jdbc"`); sem a variável aponta para `localhost:5432` e não arranca (`Connection to localhost:5432 refused`) | medido na TASK-008 com o jar contra um PostgreSQL local |
| `DB_USERNAME` | sim | utilizador do Neon | não arranca (`password authentication failed`) | medido na TASK-008 |
| `DB_PASSWORD` | sim | password do Neon | não arranca (`password authentication failed`) | medido na TASK-008 |
| `PORT` | não (o Render injeta-a) | a do Render | default `8081` | `application.yml` (`server.port`) |
| `CORS_ALLOWED_ORIGINS` | sim | `<origem do dc>,<origem do dcbo>`, separadas por vírgula, sem wildcard (domínios finais ainda por decidir: ver `project.md`, "Infraestrutura", "Por fazer") | default `http://localhost:3000`: o browser recusa os pedidos do `dc` e do `dcbo` em produção (preflight recusado) | `SecurityConfigTest#preflightFromAnUnlistedOriginIsRejected`, `#dcPreflightForCarsIsAllowed`, `#dcboPreflightWithAuthorizationForProtectedRouteIsAllowed` |
| `AUTH0_ISSUER_URI` | sim | `https://oteustand.eu.auth0.com/` | ver "Alertas" | `UnconfiguredAuth0Test`, `Auth0IssuerTest`, `MalformedAuth0IssuerTest`, `MalformedAuth0IssuerContextTest` |
| `AUTH0_AUDIENCE` | sim | `https://oteustand.pt/api` | ver "Alertas" | `UnconfiguredAuth0Test#issuerWithoutAudienceAlsoRejectsEveryTokenWithoutNetwork`, `MalformedAuth0AudienceTest` |
| `AUTH0_ROLES_CLAIM` | não | só se diferir do default `https://oteustand.pt/roles` | **nunca definida e vazia**: não cai no default e ninguém tem role (403 em tudo o que exige perfil; ver `config/CLAUDE.md`) | `SecurityConfigTest#absentOrEmptyRolesClaimGrantsNoRole` |
| `LEADS_RATE_LIMIT_TRUSTED_PROXY_HOPS` | não | `1` = só o proxy do Render; `2` se houver Cloudflare/CDN à frente | errado, o rate limit de `POST /api/leads` conta o IP de um proxy (todos os visitantes partilham a quota) ou um IP escrito pelo cliente | `RateLimitInterceptorTest#resolvesClientIpFromTheRightOfForwardedForSoASpoofedLeftEntryCannotBypassTheLimit` |
| `LEADS_RATE_LIMIT_MAX_REQUESTS` | não | default `5` | pedidos aceites por IP na janela; o seguinte dá 429 | `PublicLeadControllerTest#rejectsTheSixthSubmissionFromTheSameIpWithinTheWindow`, `PublicEndpointsHttpTest#leadsAboveTheLimitKeepTheirTooManyRequests` |
| `LEADS_RATE_LIMIT_WINDOW_MINUTES` | não | default `10` | duração da janela | `RateLimitInterceptorTest#evictExpiredEntriesRemovesIpsThatNeverSubmitAgain` (janela 0) |
| `LEADS_RATE_LIMIT_CLEANUP_INTERVAL_MS` | não | default `300000` | de quanto em quanto tempo se esquecem os IPs com a janela expirada | `CatalogBackendApplicationTests#theRateLimiterSweepIsAScheduledTask` |

### Alertas de configuração

- **`AUTH0_ISSUER_URI` exige `https://` e a barra final** (`https://oteustand.eu.auth0.com/`); o
  "Domain" do painel do Auth0 sozinho (`oteustand.eu.auth0.com`) não serve. Errado: a aplicação
  **arranca**, escreve um `WARN` `AUTH0_ISSUER_URI invalido, ...` e todos os endpoints protegidos dão
  401. Prova: `Auth0IssuerTest`, `MalformedAuth0IssuerTest`, `MalformedAuth0IssuerContextTest`.
- **`AUTH0_AUDIENCE` = `https://oteustand.pt/api`, sem espaços nem aspas.** Com um espaço ou uma
  quebra de linha à volta: `WARN` `AUTH0_AUDIENCE invalido, ...` no arranque e 401 em tudo o que é
  protegido. Prova: `MalformedAuth0AudienceTest`.
- **Perfil de token da API no Auth0 = "Auth0"** (`typ: JWT`). O perfil "RFC 9068" emite
  `typ: at+jwt` e todos os tokens dão 401 **sem nenhum `WARN`**. Prova: caso `typ at+jwt` em
  `Auth0JwtDecoderTest#invalidTokenIsRejectedAsInvalid`.
- **Health check do Render = `/actuator/health`** (público). `/actuator` e `/error` dão 401: um
  health check apontado para `/actuator` reprova todos os deploys. Prova:
  `PublicEndpointsHttpTest#actuatorHealthAndInfoArePublic`, `#actuatorIndexNeedsAToken`,
  `#errorPageAskedForDirectlyNeedsAToken`.
- **Auth0**: roles `admin`/`user` em minúsculas, no claim `https://oteustand.pt/roles`, injetado pela
  Action do fluxo Login (`project.md`, "Infraestrutura"). Na app SPA "O Teu Stand", Allowed
  Callback/Logout/Web Origins com a origem final do `dcbo` (hoje só `localhost:3000`). Prova do lado
  do código: `Auth0RolesConverterTest#mapsEachRolesClaimValueToAnUppercaseRoleAuthority`.
- **Primeiro acesso**: `app_users` nasce vazia. Um utilizador com a role `admin` no token entra sem
  linha na tabela e cria os perfis por `POST /api/backoffice/users`; só uma linha com
  `active = false` recusa (`config/ActiveUserInterceptor`). Prova:
  `ActiveUserInterceptorTest#subjectWithoutProfileIsLetThrough`,
  `AppUserControllerTest#firstAdminCanCreateUsersWithoutALocalProfile`.

### Verificação pós-deploy (Gonçalo, fora do ciclo)

Com o host do Render no lugar de `<host-render>`:

```bash
curl -s -o /dev/null -w "%{http_code}\n" https://<host-render>/actuator/health
# 200

curl -s -o /dev/null -w "%{http_code}\n" "https://<host-render>/api/backoffice/cars?size=1"
# 401 (sem token)

curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer <token real do tenant>" "https://<host-render>/api/backoffice/cars?size=1"
# 200 (utilizador com role) ou 403 (token sem role, p. ex. o do separador "Test" da API no Auth0)
# 401 = configuracao errada: voltar aos alertas acima
```

E no log do arranque no Render: nenhuma linha com `AUTH0_ISSUER_URI invalido`, `AUTH0_AUDIENCE
invalido` nem `em falta`. O resultado regista-se numa linha em `## Notas` da `RELEASE-0.1.0`.

Os três comandos foram corridos na TASK-008 contra o jar local (`AUTH0_*` vazias, PostgreSQL local):
`200`, `401`, `401`.
