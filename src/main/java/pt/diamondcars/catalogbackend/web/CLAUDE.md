# Back-office (catalog-backend, `web/`)

Factos estáveis da API do back-office (TASK-003, 2026-10-08). A segurança em si (JWT, lista
pública, 401/403, CORS) está em `../config/CLAUDE.md`; aqui fica o que é próprio das rotas do
back-office. Cada ponto tem um teste que o prova.

## Prefixo `/api/backoffice` para todo o back-office

- Todas as rotas do back-office vivem sob `BackOfficeApi.PREFIX` (`/api/backoffice`): carros
  (`/api/backoffice/cars`) e, nas tasks seguintes, clientes, parceiros, transações, finanças,
  leads, notificações, utilizadores e `me`. Não só as que colidem com o público. O `Location` dos
  `201` também é construído com a constante.
- Porquê: manter o `/api/cars` do `dcbo-backend` nem arranca (`Ambiguous mapping` com o
  `PublicCarController`) e o `GET /api/cars/{id}` do back-office ficaria público por
  `PublicEndpoints`. Um prefixo dá uma regra só para a segurança e um `baseURL` só para o `dcbo`; o
  CORS (`/api/**`) já o cobre. Não `/api/admin` (o perfil `USER` também o usa).
- **Guarda**: `config/BackOfficeSurfaceTest` percorre todos os mapeamentos e exige, para cada rota
  `/api/**` que `PublicEndpoints.MATCHER` não deixa passar sem token, (a) o prefixo
  `/api/backoffice/` e (b) `@PreAuthorize` no método ou na classe. Nunca importar
  `SecurityProbeConfig` nesse teste: a sonda (`/api/security-probe`) falharia a regra (a).
- Nomes: o público fica com o nome curto; `BackOffice*` **só** onde há gémeo público
  (`BackOfficeCarController`, `BackOfficeCarService`, `BackOfficeCarSpecifications`,
  `BackOfficeCarResponse`). O resto mantém o nome do `dcbo-backend` (`CarRequest`,
  `HighlightRequest`, `ResourceNotFoundException`, `HighlightLimitExceededException`). O código
  público (`PublicCarController`, `CarQueryService`, `CarResponse`, `CarNotFoundException`, ...)
  não se toca por causa do back-office.

## `@PreAuthorize("hasAnyRole('ADMIN', 'USER')")` na classe

- "Autenticado" não chega: um token válido do tenant **sem role** (signup na app SPA, ou
  utilizador ainda sem role na Action) criava carros e lia preços de compra. Com a anotação, 403
  `Autenticado, mas sem a role exigida para esta operacao`
  (`BackOfficeCarControllerTest#aTokenWithoutARoleIsForbiddenToListAndToCreate`).
- Um `@PreAuthorize` de método sobrepõe-se ao da classe (ex.: `DELETE` só `ADMIN`, TASK-010).
- O `@PreAuthorize` corre depois da leitura e validação do corpo: token sem role e corpo inválido
  dá 400, não 403 (só revela as regras do nosso DTO). Ordem completa: sem token 401, conta inativa
  403 (`ActiveUserInterceptor`), corpo inválido 400, role em falta 403.
- Em testes, "sem role" = `jwt().authorities(FACTOR_BEARER, SCOPE_...)`, que é o que o Spring
  Security 7 dá a um bearer token sem o claim de roles.

## DTOs: `Boolean`, não `boolean`

- Campos opcionais booleanos de um pedido são `Boolean`. Com `boolean`, o Jackson 3 do Boot 4
  (`FAIL_ON_NULL_FOR_PRIMITIVES` ligado por omissão) recusa um corpo que omita a chave com 400
  `Corpo do pedido invalido ou malformado`, sem dizer o campo; o formulário real do `dcbo` não
  envia `destaque`.
- Chaves desconhecidas são ignoradas (default do Boot): `vendido`, `reservado`, `precoVenda`,
  `clienteId`, `id` ou o alias `consignacao` no `POST` não têm efeito. Estado só muda pelos
  endpoints próprios.
- Todo o texto de input tem `@Size` igual à coluna e as listas têm teto (60 imagens, URLs até
  1000, elementos `@NotBlank`). Sem isso o valor chegava à BD e voltava como 409 `Pedido em
  conflito com o estado atual dos dados` (`DataIntegrityViolationException`), um código errado
  para um erro de input. A mensagem 400 é `campo: mensagem`, com `images[1]` para um elemento;
  os testes afirmam só o prefixo `campo: ` (o texto do Bean Validation depende do `Locale`).

## Semântica de nulo do `CarRequest`

| Campo | `POST` | `PUT` (TASK-010) |
|---|---|---|
| `observacoes` | nulo ou só espaços: `null` | nulo/ausente: mantém; `""`/espaços: limpa; texto: substitui |
| `destaque` | nulo: `false` | nulo/ausente: mantém (gere-se no `PATCH .../highlight`) |
| `images`, `imageThumbnails` | nulo: lista vazia | nulo/ausente: mantém; `[]`: remove todas |
| `isConsignacao` | nulo: `false` | nulo: `false` |
| `garantiaMeses` | nulo: 0 | nulo: 0 |

Porquê o `PUT` mantém: o `dcbo` edita com `updateDoc` do Firestore (só escreve as chaves
enviadas). Um `PUT` que anulasse o que o formulário não conhece apagava `observacoes` (texto que o
site mostra) e tirava destaques em silêncio. O cliente novo do `dcbo` não pode enviar `destaque`
no `PUT`.

## Listagens: `max-page-size` e allow-list de `sort`

- `spring.data.web.pageable.max-page-size: 100` é global: `?size=1000` dá `page.size` 100. Não afeta
  o público: o `PublicCarController` recebe `int page`/`Integer size`, não `Pageable`, e o teto
  dele é o `CarQueryService#MAX_PAGE_SIZE` (60).
- `@PageableDefault(sort = {"createdAt", "id"})`: o `id` desempata carros com o mesmo
  `createdAt`, senão as páginas repetem ou saltam carros.
- `sort` só aceita propriedades escalares da entidade (`BackOfficeCarService#SORTABLE_PROPERTIES`),
  validado antes da query: 400 `Campo de ordenacao desconhecido: <prop>`. Porquê: o Spring Data
  aceita caminhos por associações como junções. Medido no `dcbo-backend`: `sort=images.url&size=1`
  devolve o mesmo carro nas páginas 0, 1 e 2 e `totalElements` muda entre páginas;
  `partner.name`/`client.name` fazem junções em silêncio. O handler de `PropertyReferenceException`
  (mesma mensagem) no `ApiExceptionHandler` é só a rede para uma listagem sem allow-list; cada
  listagem nova do back-office com `Pageable` deve ter a sua.
- Custo fixado por teste: listar 100 carros com parceiro, cliente e 3 imagens são 5 statements
  (carros, `count`, 2 lotes de imagens pelo `@BatchSize(50)`, o `SELECT` do
  `ActiveUserInterceptor`). Parceiro e cliente nunca carregam: o DTO lê só `getId()` do proxy LAZY.
  Um campo do parceiro no DTO dá N+1 e faz falhar
  `BackOfficeCarControllerTest#listingOneHundredCarsRunsAtMostFiveStatements`. O contador é o
  `Statistics#getPrepareStatementCount` do Hibernate, ligado só durante o pedido
  (`setStatisticsEnabled(true)` em runtime funciona; não é preciso propriedade nem contexto
  próprio).

## Limite de 8 destaques: advisory lock

- `BackOfficeCarService#assertHighlightLimitRespected` chama `CarRepository#lockHighlightSlots`
  (`pg_advisory_xact_lock(hashtext('catalog-backend'), hashtext('cars.destaque'))`) **só** quando
  o pedido liga o destaque de um carro que ainda não o tem, **antes** do `countByDestaqueTrue` e
  antes de qualquer escrita da transação. O PostgreSQL liberta-o no commit/rollback. Em `READ
  COMMITTED` o `count` depois do lock tem snapshot novo e vê o destaque que o anterior gravou.
- Conta todos os `destaque = true`, vendidos incluídos (paridade com `dcbo-backend` e `dcbo`).
- Medido (`service/HighlightLimitConcurrencyTest`, mutações da TASK-003), com 7 em destaque + 3
  pedidos e com 0 + 9: count-then-save (o `dcbo-backend`) dá 10 e 9; `SELECT ... FOR UPDATE` dos
  destacados dá 8 e **9** (com 0 em destaque não há linha para trancar); advisory lock dá 8 e 8.
  `@Version` em `Car` (medido pelo arquiteto no protótipo) também 10 e 9: só protege a mesma linha. Constraint SQL não exprime "no máximo 8 linhas";
  `SERIALIZABLE` obrigava a repetir pedidos em `40001`.
- Sem deadlock porque o lock vem sempre antes de qualquer escrita na mesma transação. Duas chaves
  `int4` de propósito: não partilham o espaço `bigint` do lock do Flyway.
- Caso-limite aceite: dois pedidos simultâneos a destacar **o mesmo** carro com 7 outros em
  destaque; o segundo recebe 409 apesar de o carro ficar destacado.
- Qualquer escrita nova que ligue `destaque` (o `PUT` da TASK-010) passa pelo mesmo método.

## Testar corridas de forma determinística: `LOCK TABLE ... IN SHARE MODE`

Técnica do `HighlightLimitConcurrencyTest`, reutilizável para qualquer corrida "ler, decidir,
escrever" numa tabela:

1. Uma ligação à parte abre transação e faz `LOCK TABLE cars IN SHARE MODE`: leituras passam,
   escritas em `cars` esperam (e `SELECT ... FOR UPDATE` também passa: `ROW SHARE` não conflita).
2. Os pedidos correm em threads contra o serviço (cada um na sua transação).
3. O teste espera até `pg_stat_activity` mostrar todos os workers com `wait_event_type = 'Lock'`
   (à espera da tabela ou do advisory lock): todos já leram o que iam ler.
4. Commit da ligação; afirma o estado final e o número exato de sucessos e de recusas.

Sem sleeps nem repetição: o resultado é o mesmo em todas as corridas. Precisa de pool maior que
o default de 10 (workers + portão + a query de espera): contexto próprio com
`spring.datasource.hikari.maximum-pool-size=20`. A escrita de cada worker tem de acontecer
**depois** da decisão (aqui `saveAndFlush` no fim do método); se o código escrever antes de
decidir, o portão prende-o antes da decisão e o teste deixa de provar a corrida.
