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
| `destaque` | nulo: `false` | nulo/ausente: mantém; `false` retira; `true` sujeito ao limite de 8 |
| `images`, `imageThumbnails` | nulo: lista vazia | `images` nulo/ausente: mantém as fotos; `[]`: remove todas |
| `isConsignacao` | nulo: `false` | nulo: `false` |
| `garantiaMeses` | nulo: 0 | nulo: 0 |
| restantes opcionais | nulo: `null` | nulo: `null` (substituição total) |

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
- **Todas as listagens paginadas do back-office acabam em `id` DESC** (carros, transações,
  clientes, parceiros; TASK-013): o serviço passa o `Pageable` por `service/StableSort#withIdTiebreak`
  antes da query, que acrescenta `id` DESC quando a ordem não tem `id` (se já tem, em qualquer
  posição ou direção, fica como pedido). Porquê: um `?sort=` do cliente **substitui** o
  `@PageableDefault` inteiro, e o `id` do default perde-se; com `?sort=valor,asc` três linhas com o
  mesmo `valor` vinham na ordem física do heap e as páginas repetiam ou saltavam linhas. Nos
  serviços com allow-list a chamada vem **depois** de `requireSortable` (a validação vê só o que o
  cliente pediu). Uma listagem nova com `Pageable` chama o mesmo helper. Provado por um teste por
  listagem (`...HasIdAsLastTiebreaker...`): 3 linhas empatadas inseridas por SQL com ids `01, 02, 03`
  por essa ordem, `ANALYZE`, `size=1`; com o desempate `03, 02, 01`, sem ele `01, 02, 03`.
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
- Um teste que afirme a ordem por `createdAt` tem de fixar o `created_at` (`UPDATE` por JDBC):
  o `@CreationTimestamp` lê o relógio da JVM, que no Windows avança em ticks de ~15,6 ms, e três
  `saveAndFlush` seguidos ficam muitas vezes com o mesmo instante (medido: 20 saves deram 11
  instantes distintos). Aí quem decide é o desempate por `id` aleatório e o teste falha 1 vez em
  cada poucas (`listsCarsMostRecentlyCreatedFirstByDefault`, corrigido na TASK-010).

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
- Sem deadlock porque o lock vem sempre antes de qualquer escrita na mesma transação, e sempre
  **depois** do lock de linha do carro (TASK-010: `highlight` e `PUT` trancam o carro primeiro, ver
  "Vendas, reversões e concorrência"). O `create` só pede o advisory (o carro novo não é visível a
  mais ninguém). Duas chaves `int4` de propósito: não partilham o espaço `bigint` do lock do Flyway.
- Caso-limite aceite: dois pedidos simultâneos a destacar **o mesmo** carro com 7 outros em
  destaque; com o lock de linha da TASK-010 o segundo espera pelo primeiro, vê o carro já
  destacado e responde 200 sem pedir o advisory.
- Qualquer escrita que ligue `destaque` passa pelo mesmo método: o `PUT` da TASK-010 com
  `destaque: true` num carro sem destaque, antes de qualquer escrita (um 409 aí não grava nada, nem a
  reconciliação de um carro vendido).

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

Variante com locks de linha (`service/CarSaleConcurrencyTest`, TASK-010): o portão é `LOCK TABLE
cars, clients, partners IN SHARE MODE`. `SELECT ... FOR NO KEY UPDATE` pede `ROW SHARE` e passa;
um worker que espera pela linha que outro trancou também conta como `wait_event_type = 'Lock'`. Por
isso o número a esperar é sempre o de workers, quer esperem pelo portão quer uns pelos outros. As
tabelas fora do portão (`transactions`, `leads`) escrevem sem esperar: o `INSERT`/`DELETE` de uma
transação de venda corre antes do portão abrir, e só pede `FOR KEY SHARE` às linhas referenciadas.
Para provar uma corrida contra um `DELETE` (K8) o portão é a própria ligação que apagou a linha sem
commit.

## Clientes e parceiros (TASK-004)

`/api/backoffice/clients` e `/api/backoffice/partners` seguem a mesma convenção dos carros, com
duas diferenças deliberadas:

- **Sem allow-list própria de `sort`.** `Client` e `Partner` são entidades planas, sem coleção
  nem associação exposta no DTO (ao contrário de `Car`, com `images`/`partner`/`client` LAZY) —
  não há junção para uma ordenação maliciosa explorar. O handler genérico de
  `PropertyReferenceException` (TASK-003 AC H) já responde 400 a qualquer propriedade
  inexistente; uma allow-list só se justifica para bloquear uma junção real.
- **Ordem por omissão `createdAt` DESC, desempatada por `id` DESC** (TASK-013, via
  `StableSort#withIdTiebreak` no serviço; o `@PageableDefault` dos controllers continua só com
  `createdAt`). Antes, clientes ou parceiros com o mesmo `created_at` repetiam ou saltavam entre
  páginas mesmo sem `?sort=` (medido pelo arquiteto: ordem física `01, 02, 03`).
- **`ResourceInUseException` (409)** em vez de deixar o `DataIntegrityViolationException` da FK
  chegar ao cliente: `ClientService#delete`/`PartnerService#delete` verificam
  `existsByClientId`/`existsByPartnerId` antes de apagar, com a mensagem a nomear o recurso.

`PartnerService` tem `incrementCarsCount`/`decrementCarsCount`/`registerCommission`/
`reverseCommission`, chamados por `BackOfficeCarService#sell`/`#revertSale`/`#update` (este só num
carro vendido) desde a TASK-010. Mutam só o objeto em memória (sem repositório; o dirty checking
grava no commit), provados por `PartnerServiceCountersTest` (unitário puro). O chamador passa-lhes
um parceiro carregado com `PartnerRepository#findByIdForUpdate` (linha trancada).

`PartnerService#update`, `ClientService#update` e `ClientService#delete` leem com
`findByIdForUpdate` (TASK-010): o Hibernate grava a linha inteira com os valores que leu, por isso
editar o nome durante uma venda repunha o contador antigo (K5/K6), e um `delete` sem lock podia
passar o "não tem carros" no instante em que uma venda lhe atribuía um (K7). No `delete` o lock vem
antes do `existsByClientId`. `PartnerService#delete` **não** tranca: a corrida dele é contra a
verificação de FK do `create`/`PUT` de carros (`FOR KEY SHARE`), que um `FOR NO KEY UPDATE` não
trava; fica para a TASK-015 (`FOR UPDATE` explícito).

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
- **3 métodos da venda**, chamados por `BackOfficeCarService` desde a TASK-010: `createSaleTransaction`
  (`sell`), `deleteSaleTransactions` (`revertSale`, só `system_generated`), `deleteAllForCar`
  (`delete` com `deleteTransactions=true`, manuais incluídas). Propagação `REQUIRED`, nunca
  `REQUIRES_NEW`: correm dentro da transação do `sell`/`revertSale`/`delete` do carro e uma falha
  depois deles desfaz o movimento financeiro
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

## Vendas, reversões e concorrência (TASK-010)

`PUT`, `DELETE` (só `ADMIN`), `POST .../sell` e `POST .../revert-sale` em `/api/backoffice/cars/{id}`.
Os testes estão em `BackOfficeCarControllerTest` (secções TASK-010), `TransactionControllerTest`
(T1 a T6 e summary), `PartnerControllerTest` (P1 a P6) e `service/CarSaleConcurrencyTest` (K1 a K10).

**Semântica** (paridade com `dcbo-backend`, que só mexe nos contadores na venda e na reversão):

- **Venda**: `vendido`, `precoVenda`, `dataVenda` (agora), cliente; `purchases_count + 1` do cliente.
  Carro em consignação com parceiro: comissão em `total_commission`, `cars_count + 1` e transação
  `comissao_venda` ("Comissao: marca modelo", valor = comissão, `0.00` se não houver, com parceiro e
  cliente). Caso contrário: `venda_veiculo` ("Venda: marca modelo", valor = `precoVenda`, sem
  parceiro, mesmo que o carro tenha um). Carro já vendido: 409 `Carro ja vendido: <id>`
  (`CarAlreadySoldException`), lançado logo a seguir ao lock do carro, antes de qualquer outro lock
  ou escrita. Cliente inexistente: 404 sem escrever nada.
- **Reversão**: desfaz exatamente o que a venda registou com os valores **atuais** do carro
  (cliente com piso 0, comissão e `cars_count` do parceiro), apaga só a transação
  `system_generated` do carro (uma manual nunca) e limpa os 4 campos da venda. Carro não vendido:
  **no-op 200**, nada muda (o `dcbo-backend` chegou a descontar comissão por uma venda que não
  existiu, também numa segunda reversão).
- **`PUT` de carro vendido**: `reconcileConsignmentSaleAfterEdit` tira a comissão e um carro ao
  parceiro em que a venda registou (se era consignação) e põe-nos no parceiro/comissão novos (se é),
  para a reversão seguinte desfazer o certo. **ASSUNÇÃO (AC C.4)**: a transação `comissao_venda`
  **não** acompanha a edição (paridade); o resumo financeiro mostra a comissão original até a
  venda ser revertida, e a reversão apaga-a. `PUT` de carro não vendido não toca em contadores nem
  em transações.
- **`DELETE`**: `deleteTransactions=true` (omissão, a checkbox do `dcbo`) apaga todas as transações
  do carro e, se estava vendido, decrementa o cliente (piso 0); `false` deixa-as com `carroId` nulo
  (`transactions.car_id ON DELETE SET NULL`) e não mexe em contadores. O parceiro nunca muda.
  `?deleteTransactions=abc` dá 400 `Parametro 'deleteTransactions' com valor invalido`. Fotos saem
  com o carro (`orphanRemoval` + `ON DELETE CASCADE`); leads ficam com `car_id` nulo.
- **`precoVenda`**: `@DecimalMin("100")`, teto `CarRequest.PRICE_MAX_VALUE` (acima: 400, nunca o 409
  de um overflow de `numeric(12,2)`) e `@Digits(integer = 8, fraction = 2)` (novo face ao
  `dcbo-backend`: `20000.005` era gravado `20000.01` com a resposta a ecoar `20000.005`).

**Concorrência: lock de linha, sem coluna nova, isolamento `READ COMMITTED`.** O Hibernate grava a
linha inteira com os valores que leu (sem `@DynamicUpdate`, sem `@Version`), por isso qualquer
ler-alterar-gravar sem lock de um carro, parceiro ou cliente desfaz o que outra transação gravou
entretanto, e não só nos contadores. Medido em PostgreSQL real (arquiteto, e cada linha morta por
uma mutação no PR):

| # | Corrida | Sem lock | Com lock |
|---|---|---|---|
| K1 | 2 `sell` do mesmo carro ao mesmo cliente | 2 vendas, `purchases_count` 2, 2 transações | 1 + 1 409, 1, 1 |
| K2 | 4 `sell` de 4 carros em consignação do mesmo parceiro (100.25) | `cars_count` 1, 100.25 | 4, 401.00 |
| K3 | `revert-sale` do carro A e `sell` do B, mesmo cliente | `purchases_count` 0 | 1 |
| K4 | `sell` e `reserve` do mesmo carro | `vendido` false (a venda some do carro) | `vendido` e `reservado` |
| K5 | `sell` (consignação) e `PUT` do parceiro (nome) | `cars_count` 0 | 1 e o nome novo |
| K6 | `sell` e `PUT` do comprador | `purchases_count` 0 | 1 e o nome novo |
| K7 | `sell` ao cliente C e `DELETE` de C | carro vendido sem cliente | um ganha, dados coerentes |
| K8 | lead com o `carroId` de um carro cujo `DELETE` não fez commit | 409, lead perdido | lead com `car_id` nulo |
| K9 | 2 `revert-sale` do mesmo carro | `purchases_count` 0 | 1 |
| K10 | `PUT` de vendido do parceiro A para B e `sell` de outro carro de B | B com 1 | A 0, B 2 |

Regras (cada uma com o cenário que a prova):

- **Carro**: `CarRepository#findByIdForUpdate` é a primeira leitura do carro em toda a operação que
  escreve num carro existente (`update`, `delete`, `sell`, `revertSale`, `reserve`,
  `releaseReservation`, `highlight`). Só o `get` usa `findById`. O `create` só pede o advisory.
- **Parceiro e cliente**: `findByIdForUpdate` antes de mexer em `cars_count`/`total_commission`/
  `purchases_count` (`sell`, `revertSale`, `update` de vendido, `delete` com transações de vendido) e
  em `PartnerService#update`, `ClientService#update`/`#delete`.
- **Ordem, sempre a mesma** (sem ciclos): carro, advisory do destaque (só quando liga um destaque),
  parceiros por `id` crescente (no `PUT` de um vendido: o da venda e o pedido, o mesmo só uma vez),
  cliente. Tudo antes da primeira escrita do método.
- **Armadilha da primeira carga** (medida): o lock tem de ser a primeira carga dessa entidade na
  transação. Um `Hibernate.initialize(car.getPartner())`, um getter de estado do parceiro ou um
  `findById` antes do `findByIdForUpdate` faz o `SELECT ... for no key update` correr, mas o Hibernate
  devolve o estado que já tinha em memória (mutação M9: K2 dá `cars_count` 1). Ler só
  `car.getPartner().getId()` (proxy `LAZY` por inicializar) é seguro. No `PUT` de um vendido o
  parceiro pedido é carregado diretamente pelo lock, nunca primeiro pelo `requirePartner`.
- **SQL real**: no Hibernate do Boot 4.1.1 o `PESSIMISTIC_WRITE` em PostgreSQL é `for no key update`.
  Não entra em conflito com o `FOR KEY SHARE` das verificações de FK: gravar um lead ou uma transação
  manual com esse `car_id` não espera por uma venda nem por um `PUT`; só o `DELETE` do carro os
  bloqueia. O `POST /api/leads` pede `CarRepository#lockKeyShare` (`FOR KEY SHARE`, o lock mais fraco
  que trava um `DELETE`) e depois `getReferenceById`: se o `DELETE` já tem a linha, o lead espera e
  grava sem carro; se o lead chega primeiro, o `DELETE` espera e o `ON DELETE SET NULL` limpa-o.
- **Rejeitado**: `@Version` em `Car` (coluna nova, e não protege as linhas de parceiro/cliente
  partilhadas por carros diferentes: K2, K3, K5, K6), `SERIALIZABLE`/`REPEATABLE READ` (obrigam a
  repetir pedidos em `40001`), advisory global de vendas (serializa vendas sem nada em comum). Sem
  `lock_timeout`: nenhuma destas transações faz I/O externo, cada lock dura poucos statements.
