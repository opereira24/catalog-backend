# Modelo de dados (catalog-backend)

Factos estáveis do modelo de dados unificado (TASK-001, 2026-10-05). O `catalog-backend` é o
único backend do projeto: guarda na mesma BD (Neon em produção) o catálogo público e os dados
do back-office portados do `dcbo-backend`.

## Migrações Flyway (`src/main/resources/db/migration/`)

- **`V1__init.sql` é imutável.** Já foi aplicado no Neon; qualquer edição muda o checksum e o
  Flyway recusa arrancar. Toda a evolução é por migração nova (`V3`, `V4`, ...).
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
  (migrar até à versão anterior, inserir por SQL, migrar, comparar).

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
  `synced_at`, `forwarded_at` e `forward_attempts` não são públicos e saem na migração de
  limpeza do encaminhamento para o `dcbo-backend`.
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
- Associações `@ManyToOne` sempre `LAZY`. `lead.getCar().getId()` fora de transação é seguro
  (o Hibernate responde ao id do proxy sem o carregar, `LeadForwardPayload#from`); qualquer
  outro getter do carro fora de transação dá `LazyInitializationException`.
