# Modelo de dados (catalog-backend)

Factos estáveis do modelo de dados unificado (TASK-001, 2026-10-05). O `catalog-backend` é o
único backend do projeto: guarda na mesma BD (Neon em produção) o catálogo público e os dados
do back-office portados do `dcbo-backend`.

## Migrações Flyway (`src/main/resources/db/migration/`)

- **`V1__init.sql` é imutável.** Já foi aplicado no Neon; qualquer edição muda o checksum e o
  Flyway recusa arrancar. Toda a evolução é por migração nova (`V3`, `V4`, ...). O mesmo vale
  para cada migração depois de mergeada (os comentários antigos da V2 sobre o encaminhamento
  ficam: mudá-los mudava o checksum).
- **`V2__unified_back_office_schema.sql` é o modelo unificado**: cria `partners`, `clients`,
  `transactions`, `notifications`, `app_users` (iguais ao `dcbo-backend`) e acrescenta a
  `cars`, `car_images` e `leads` as colunas do back-office. Um carro e um lead são **uma**
  tabela cada: o registo que o site mostra é o que o back-office gere.
- No PostgreSQL o Flyway corre cada migração numa transação. Uma migração que falhe deixa a BD
  na versão anterior, sem estado intermédio, e o arranque falha. Escrever migrações que contem
  com isto (um ficheiro por passo coerente) e que corram sobre dados reais.
- Uma migração que valide dados antes de criar uma constraint (ex.: limpar órfãos antes de uma
  FK) tem de bloquear a tabela primeiro: durante um deploy no Render a instância antiga continua
  a escrever. A V2 faz `LOCK TABLE leads IN SHARE ROW EXCLUSIVE MODE` antes de limpar os
  `car_id` órfãos; sem isso, um insert concorrente com `car_id` órfão fazia a FK falhar e o
  deploy cair (medido com dois `psql` em paralelo, TASK-001).
- `V1ToV2UpgradeTest` prova o upgrade sobre dados V1 numa schema própria (`upgrade_v1_v2`) com
  `target("2")`. Uma migração nova que mexa em dados existentes merece um teste do mesmo tipo
  (migrar até à versão anterior, inserir por SQL, migrar, comparar). `V2ToV3UpgradeTest` compara
  todas as colunas que sobrevivem, com a lista lida de `information_schema.columns` antes da
  migração (não precisa de manutenção quando o esquema cresce).
- **Migração que altere tabelas existentes: 1.ª linha `SET LOCAL lock_timeout = '10s';`, depois
  um único `LOCK TABLE` com as tabelas por ordem `cars`, `leads` (a da aplicação), antes de
  qualquer `ALTER`.** Provado por `migration/V3LockTest` (TASK-006). Porquê: o deploy no Render é
  sem interrupção e a instância antiga continua a ler e escrever enquanto a nova corre o Flyway.
  Sem `lock_timeout`, uma transação aberta da instância antiga prende a migração sem limite e
  todas as leituras dessas tabelas ficam em fila atrás do `ACCESS EXCLUSIVE` dela; com ele, a
  migração falha ao fim de 10 s com `55P03`, a BD fica na versão anterior e o deploy repete-se.
  `SET LOCAL`, nunca `SET` (não fica numa ligação do pool). A ordem: `POST /api/leads` tranca o
  carro (`FOR KEY SHARE`) e depois insere em `leads`; com `leads` primeiro dá `deadlock detected`
  (medido: no teste a vítima é a migração, que esperava há mais tempo; o arquiteto mediu-o com o
  `INSERT` do lead como vítima). Tabela nova na lista: pô-la na posição em que a aplicação a
  tranca.

## Factos do esquema que não se adivinham pelo código

- **`cars.id` é gerado** (`DEFAULT gen_random_uuid()` + `@UuidGenerator`). Já não vem do
  `dcbo-backend`. Nunca pôr `.id(...)` num `Car` novo: com id gerado, um id preenchido faz o
  Spring Data tratar a entidade como existente (`merge` em vez de `INSERT`).
- **`leads.car_id` é FK para `cars` com `ON DELETE SET NULL`.** Apagar um carro mantém os
  leads, sem carro; o snapshot `carro_marca`/`carro_modelo`/`carro_preco` é o que continua a
  dizer de que carro era o lead.
- **`POST /api/leads` com um `carroId` que não existe grava o lead sem carro e responde 201**,
  nunca 404/500 (`LeadService#findCar`). A `origem` (`website` vs `website-contacto`) é decidida
  pela presença de `carroId` no pedido, não pela existência do carro.
- **`leads.status` tem default `'ativo'` na BD** (o `dcbo-backend` tinha `'contactado'`): todos os
  leads anteriores à V2 vieram do site, e `ativo` é o que o site sempre escreveu. O
  `@Builder.Default` do `Lead` em Java continua `CONTACTADO` (herdado do `dcbo-backend`); quem cria
  leads grava o estado explicitamente (`createFromWebsite` grava `ATIVO`).
- **`transmissao`/`origem` dos carros anteriores à V2 são `Manual`/`Nacional` por backfill**, não
  dados reais (valores iniciais do formulário do `dcbo`). O default foi retirado logo a seguir:
  um carro novo tem de trazer os dois valores.
- **`car_images.created_at` das fotos anteriores à V2 é o `created_at` do carro** (a data real
  da foto não existe).
- **`observacoes` é público**: está no `CarResponse` e o `dc` mostra-o na página do carro.
  As colunas do encaminhamento para o `dcbo-backend` (uma em `cars`, duas em `leads`) saíram na
  V3 (TASK-006); os leads nunca encaminhados ficaram como leads normais.
- **`Lead.car` é `updatable = false`**: o carro de um lead fixa-se na criação. O Hibernate grava a
  linha inteira, e sem isto uma edição podia repor o `car_id` de um carro já apagado (FK, 409).
  Prova em `service/BackOfficeLeadConcurrencyTest#anEditNeverWritesBackTheCarOfALead`.
- **Contrato público congelado**: cada carro de `GET /api/cars`, `/api/cars/{id}` e
  `/api/cars/highlights` tem exatamente 18 chaves (`PublicCarControllerTest#PUBLIC_CAR_KEYS`).
  Um campo novo na entidade nunca aparece no site por acaso; acrescentar uma chave é uma decisão
  de contrato.
- **ADR-001 camada 1 é testada pela positiva**: `ForeignKeyNamingConventionTest` exige que cada
  FK do schema `public` se chame `<tabela referenciada sem o s final>_id` e fixa a lista de FKs.
  Uma FK nova entra nessa lista.

## Domínio Java (`domain/`)

- Hierarquia única: `AbstractDomainEntity` (id gerado + `created_at`) e
  `AbstractAuditableDomainEntity` (+ `updated_at`), iguais ao `dcbo-backend`.
- Repositórios só com métodos que têm consumidor em código de produção; não portar métodos que
  só os testes do `dcbo-backend` usavam.
- `created_at` é escrito pelo `@CreationTimestamp` com o relógio da JVM (ticks de ~15,6 ms no
  Windows, medição em `web/CLAUDE.md`): gravações seguidas empatam com frequência, e com empate o
  PostgreSQL não garante ordem (numa tabela pequena devolve-as, na prática, pela ordem física, a
  de inserção). Um teste que afirme ordem por
  `created_at` fixa os instantes por `UPDATE` nativo numa ordem diferente da de inserção e do seu
  inverso, para que sem o `UPDATE` falhe sempre e não só em alguns ticks
  (`CarRepositoryTest#setCreatedAt`, TASK-011). Uma query paginada ordenada por `created_at` leva
  sempre `id` como desempate (`CarQueryService#DEFAULT_SORT`,
  `findByDestaqueTrueAndVendidoFalseOrderByCreatedAtDescIdAsc`).
- **Escrita que devolve um DTO com `createdAt`/`updatedAt` usa `saveAndFlush`.** O
  `@CreationTimestamp`/`@UpdateTimestamp` só é preenchido quando o Hibernate corre o
  `INSERT`/`UPDATE`, ou seja, no flush; com `save` (id gerado) ou só dirty-checking o serviço mapeia
  o DTO antes disso e os timestamps saem `null` (create) ou antigos (update). Medido na TASK-020 em
  8 métodos de `ClientService`, `PartnerService`, `TransactionService` e `AppUserService`. Um
  `PUT`/`PATCH` que não muda nenhum campo continua a não gravar nada e a devolver o `updatedAt`
  anterior (correto). Prova: `*ControllerTest#*CarriesThe*` (TASK-020), que comparam a resposta com
  a linha na BD por `support/WrittenTimestamps`.
- Associações `@ManyToOne` sempre `LAZY`. `lead.getCar().getId()` fora de transação é seguro
  (o Hibernate responde ao id do proxy sem o carregar, `BackOfficeLeadResponse#from`); qualquer
  outro getter do carro fora de transação dá `LazyInitializationException`.
