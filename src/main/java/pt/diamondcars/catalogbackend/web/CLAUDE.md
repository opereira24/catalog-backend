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
`spring.datasource.hikari.maximum-pool-size=20` **e `@DirtiesContext`**, que fecha as 20 ligações
logo a seguir à classe em vez de as guardar até ao fim da JVM.

Orçamento de ligações do contentor de teste: `max_connections` **300** (TASK-012,
`AbstractPostgresIntegrationTest#MAX_CONNECTIONS`, fixado por
`support/PostgresTestContainerSettingsTest`). Cada contexto em cache segura o pool cheio (o Hikari
enche-o até ao máximo), por isso cada configuração de contexto nova (outras propriedades,
`@Import`, `@MockitoBean`) custa 10 ligações enquanto a JVM viver. Hoje há 9 contextos de 10 (pico
medido 91/300 com a sonda); cabem ~22 em cache, mais o transitório de 20 deste teste, antes de
passar 80% do limite. Acima disso, revisitar: subir o limite ou baixar `minimum-idle` nos testes.
Histórico: com o limite por omissão do PostgreSQL (100, todas utilizáveis: o utilizador `test` é
superuser e também ocupa as 3 reservadas) e sem o `@DirtiesContext`, o contexto de 20 ficava em
cache e o 10.º contexto falhava a arrancar com `FATAL: sorry, too many clients already` (TASK-003,
medido no `mvnw clean verify`).

A escrita de cada worker tem de acontecer **depois** da decisão (aqui `saveAndFlush` no fim do
método); se o código escrever antes de decidir, o portão prende-o antes da decisão e o teste
deixa de provar a corrida.

## Clientes e parceiros (TASK-004)

`/api/backoffice/clients` e `/api/backoffice/partners` seguem a mesma convenção dos carros, com
duas diferenças deliberadas:

- **Sem allow-list própria de `sort`.** `Client` e `Partner` são entidades planas, sem coleção
  nem associação exposta no DTO (ao contrário de `Car`, com `images`/`partner`/`client` LAZY) —
  não há junção para uma ordenação maliciosa explorar. O handler genérico de
  `PropertyReferenceException` (TASK-003 AC H) já responde 400 a qualquer propriedade
  inexistente; uma allow-list só se justifica para bloquear uma junção real.
- **`ResourceInUseException` (409)** em vez de deixar o `DataIntegrityViolationException` da FK
  chegar ao cliente: `ClientService#delete`/`PartnerService#delete` verificam
  `existsByClientId`/`existsByPartnerId` antes de apagar, com a mensagem a nomear o recurso.

`PartnerService` ganhou `incrementCarsCount`/`decrementCarsCount`/`registerCommission`/
`reverseCommission` **sem nenhum chamador ainda** — mutam só o objeto em memória (sem
repositório), provados por `PartnerServiceCountersTest` (unitário puro). A TASK-010 liga-os a
`BackOfficeCarService#sell`/`revertSale`/`update` quando os criar.

## Transações e finanças (TASK-005)

`/api/backoffice/transactions` (CRUD) e `/api/backoffice/finances/summary` seguem a convenção dos
carros. O que não se adivinha pelo código:

- **Allow-list de `sort`** (`TransactionService#SORTABLE_PROPERTIES`, só escalares). Ao contrário de
  `Client`/`Partner`, a `Transaction` tem 3 associações `LAZY` (`car`, `client`, `partner`): sem a
  lista, `sort=car.marca` ou `sort=partner.name` eram junções aceites pelo Spring Data.
- **Ordem por omissão `data, createdAt, id` DESC.** Várias transações no mesmo dia são o caso
  normal (compra e venda do mesmo carro, despesas do dia); só com `data` as páginas repetiam ou
  saltavam linhas. `TransactionControllerTest#defaultOrderIsDataThenCreatedAtThenIdDescendingAndPagesAreStable`
  insere por SQL com `created_at` crescente para que a falta de desempate apareça como ordem física,
  e dá à mais antiga do dia o **maior** `id`: com `id` a crescer junto com `created_at`, `data, id`
  (sem `createdAt`) dava a mesma ordem e o teste não provava o desempate (review r1 da TASK-005).
- **`?tipo=` por `PropertyEditor`, não por `Converter`** (`TransactionTypeQueryConverter`, registado
  no `@InitBinder` do controller). Medido: um `Converter<String, TransactionType>` bean recusa
  `VENDA`, mas o `TypeConverterDelegate` do Spring apanha o `ConversionFailedException` e tenta
  `Enum.valueOf`, por isso `?tipo=VENDA` dava 200. Um editor próprio é consultado antes do
  `ConversionService` e não tem esse recurso. Vale para qualquer enum de query que tenha de recusar
  o nome da constante Java. Um editor também perde o "vazio = sem filtro" que o Spring dá a
  qualquer enum: `?tipo=` vazio ou só com espaços tem de devolver `null` explicitamente no
  `setAsText`, senão é 400 (o "Todos" de um formulário envia o campo vazio, e `?carroId=`/`?from=`
  vazios da mesma rota não filtram).
- **`valor` com `@Digits(integer = 8, fraction = 2)`**: `NUMERIC(12,2)` arredonda `10.005` para
  `10.01` sem erro, e o 201 devolvia `10.005` (a entidade em memória não é relida). Sempre positivo:
  o sentido do dinheiro vem só do `tipo`.
- **`data` em `[0001-01-01, 9999-12-31]` e até 64 caracteres** (`TransactionRequest#resolveData`).
  `LocalDate.parse` aceita `+10000-01-01` e `0000-12-31`; gravados, ficavam fora das datas sentinela
  do summary. A mensagem de erro repete o valor, daí o teto de tamanho.
- **Filtros `from`/`to` fora do `DATE` do PostgreSQL** (`?from=-5000-01-01` e
  `?to=+999999999-12-31` passam no `@DateTimeFormat(iso = DATE)`): `TransactionService#lowerBound`/
  `upperBound` trazem-nos para `[MIN_DATA - 1 dia, MAX_DATA + 1 dia]`, o que não muda nenhum
  resultado (nenhuma `data` gravada está fora de `[MIN_DATA, MAX_DATA]`) e evita o `date out of range`
  do PostgreSQL, que chegava ao cliente como 409 `Pedido em conflito` (o Spring traduz o
  `DataException` para `DataIntegrityViolationException`; medido na mutação 14 da TASK-005).
- **`systemGenerated`, `clienteId`, `partnerId` só pelo serviço.** O corpo do pedido não os
  declara; chaves desconhecidas são ignoradas. É uma garantia de segurança: uma transação marcada
  `systemGenerated` pelo cliente seria apagada pela reversão de uma venda (TASK-010).
- **`PUT` muda só os 5 campos de negócio** (`tipo`, `valor`, `descricao`, `categoria`, `data`) e
  ignora `carroId`, também em transações de sistema: o "Editar" do `dcbo` abre todas, e reaplicar
  `carroId` desligava uma venda do seu carro na primeira edição. `PUT`/`DELETE` são permitidos em
  transações de sistema a `ADMIN` e `USER` (paridade com `dcbo`).
- **`InvalidReferenceException` dá 400, não 404**: `carroId` inexistente é um campo do corpo, não o
  recurso do URL (o `partnerId` de `CarRequest` dá 404 por precedente próprio).
- **3 métodos para a TASK-010**, sem chamador ainda: `createSaleTransaction`,
  `deleteSaleTransactions` (só `system_generated`), `deleteAllForCar` (manuais incluídas).
  Propagação `REQUIRED`, nunca `REQUIRES_NEW`: correm dentro da transação do `sell`/`revertSale`/
  `delete` do carro e uma falha depois deles desfaz o movimento financeiro
  (`TransactionServiceTest#createSaleTransactionRollsBackWithTheCallersTransaction`).
- **`Clock` por construtor package-private, não bean.** O construtor público usa
  `Clock.systemUTC()`; a data da venda é sempre `LocalDate.ofInstant(clock.instant(), Europe/Lisbon)`.
  `TransactionServiceSaleDateTest` (sem Spring, zero ligações) fixa o instante. Um bean `Clock`
  convidava outro código a depender dele e substituí-lo num teste custava um contexto novo (10
  ligações).
- **Summary**: duas somas em `READ COMMITTED`; uma transação gravada entre elas pode entrar num total
  e não no outro, o `saldo` é sempre a diferença dos dois números devolvidos. Aceite para um ecrã de
  resumo. Números com exatamente 2 casas (`BigDecimal` escala 2): os testes afirmam o texto
  (`"saldo":-50.00`), não o valor do JsonPath, que perde as casas.
- Custo fixo: listar 100 transações com carro, cliente e parceiro são no máximo 3 statements
  (página, `count`, `ActiveUserInterceptor`); o DTO só lê `getId()` dos proxies.
