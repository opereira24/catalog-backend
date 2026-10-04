-- V2__unified_back_office_schema.sql
-- Modelo de dados unificado: o catalog-backend passa a ser o unico backend (TASK-001, decisao de
-- 2026-10-04) e guarda, na mesma BD, o catalogo publico e os dados internos do back-office que
-- hoje so existem no dcbo-backend (V1+V2+V3 dele). Um carro e um lead sao UMA tabela cada: o que
-- o site publico mostra e o que o back-office gere e o mesmo registo.
--
-- Regras desta migracao:
--   * V1__init.sql ja foi aplicado no Neon em producao e nunca se edita (o checksum do Flyway
--     partia o arranque). Tudo aqui e aditivo: nenhuma coluna, tabela ou linha e removida.
--   * No PostgreSQL o Flyway corre cada migracao numa transacao: ou entra tudo ou nada. Se algo
--     falhar, a BD fica em V1 sem estado intermedio e o arranque falha.
--   * Corre sobre carros e leads reais. Colunas novas NOT NULL recebem valor para as linhas
--     existentes; nenhum lead e apagado (os car_id orfaos passam a NULL, o snapshot
--     carro_marca/carro_modelo fica).
--   * ADR-001 (projects/diamondcars/project.md): FKs em ingles (<tabela_singular>_id), atributos
--     de negocio como o React os escreve. ForeignKeyNamingConventionTest verifica.
--
-- Convencoes herdadas: snake_case, UUID gerado pela BD (gen_random_uuid(), nativo no PG 13+),
-- NUMERIC(12,2) para dinheiro, DATE para datas de calendario vindas de <input type="date">,
-- TIMESTAMPTZ para instantes.

-- 1. Parceiros e clientes (identicas ao dcbo-backend V1).

CREATE TABLE partners (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(255) NOT NULL,
    email             VARCHAR(255),
    phone             VARCHAR(50),
    notes             TEXT,
    cars_count        INTEGER NOT NULL DEFAULT 0,
    total_commission  NUMERIC(12,2) NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE clients (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name              VARCHAR(255) NOT NULL,
    email             VARCHAR(255),
    phone             VARCHAR(50) NOT NULL,
    nif               VARCHAR(20),
    address           VARCHAR(500),
    postal_code       VARCHAR(20),
    notes             TEXT,
    purchases_count   INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_clients_email ON clients (email);
CREATE INDEX idx_clients_nif ON clients (nif);

-- 2. cars: a tabela existente ganha os campos internos do back-office.
-- O id deixa de vir do dcbo-backend: passa a ser gerado aqui.
-- transmissao/origem: 'Manual'/'Nacional' sao os valores iniciais do formulario do dcbo
-- (dcbo/src/components/cars-form.js) e os unicos que os filtros de dcbo/src/pages/cars.js
-- conhecem. O default so serve para preencher os carros anteriores a esta migracao (backfill, nao
-- dado real; o back-office corrige por carro) e e retirado logo a seguir, como no dcbo-backend.

ALTER TABLE cars
    ALTER COLUMN id SET DEFAULT gen_random_uuid(),
    ADD COLUMN transmissao       VARCHAR(50) NOT NULL DEFAULT 'Manual',
    ADD COLUMN origem            VARCHAR(100) NOT NULL DEFAULT 'Nacional',
    ADD COLUMN preco_compra      NUMERIC(12,2),
    ADD COLUMN is_consignacao    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN partner_id        UUID REFERENCES partners (id) ON DELETE SET NULL,
    ADD COLUMN commission_value  NUMERIC(12,2),
    ADD COLUMN data_compra       DATE,
    ADD COLUMN data_venda        TIMESTAMPTZ,
    ADD COLUMN preco_venda       NUMERIC(12,2),
    ADD COLUMN client_id         UUID REFERENCES clients (id) ON DELETE SET NULL;

ALTER TABLE cars
    ALTER COLUMN transmissao DROP DEFAULT,
    ALTER COLUMN origem DROP DEFAULT;

-- Servem existsByPartnerId/existsByClientId/findBy...OrderByCreatedAtDesc e o ON DELETE SET NULL
-- ao apagar um parceiro/cliente (o dcbo-backend nao os tinha).
CREATE INDEX idx_cars_partner_id ON cars (partner_id);
CREATE INDEX idx_cars_client_id ON cars (client_id);

-- 3. car_images: created_at. A data real de cada foto nao existe; a do carro e o limite inferior
-- honesto (melhor do que a hora desta migracao).

ALTER TABLE car_images ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE car_images ci SET created_at = c.created_at FROM cars c WHERE c.id = ci.car_id;

-- 4. transactions: ja no estado final do dcbo-backend (V1 + rename V2 + system_generated V3).

CREATE TABLE transactions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo              VARCHAR(50) NOT NULL,
    valor             NUMERIC(12,2) NOT NULL,
    descricao         VARCHAR(500),
    categoria         VARCHAR(100),
    data              DATE NOT NULL,
    car_id            UUID REFERENCES cars (id) ON DELETE SET NULL,
    client_id         UUID REFERENCES clients (id) ON DELETE SET NULL,
    partner_id        UUID REFERENCES partners (id) ON DELETE SET NULL,
    system_generated  BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transactions_car_id ON transactions (car_id);
CREATE INDEX idx_transactions_categoria ON transactions (categoria);
CREATE INDEX idx_transactions_data ON transactions (data DESC);
CREATE INDEX idx_transactions_client_id ON transactions (client_id);
CREATE INDEX idx_transactions_partner_id ON transactions (partner_id);

-- 5. leads: a tabela existente ganha o fluxo do back-office e leads.car_id passa a FK.
--
-- O lock vem primeiro: durante um deploy no Render a instancia antiga continua a aceitar
-- POST /api/leads. Sem ele, um lead com car_id orfao inserido entre a limpeza e o ADD CONSTRAINT
-- fazia a validacao da FK falhar e o deploy cair. SHARE ROW EXCLUSIVE bloqueia escritas
-- concorrentes e deixa as leituras passar; dura ate ao fim desta transacao.

LOCK TABLE leads IN SHARE ROW EXCLUSIVE MODE;

-- Leads cujo carro ja nao existe ficam sem carro, nunca sao apagados.
UPDATE leads l
SET car_id = NULL
WHERE l.car_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM cars c WHERE c.id = l.car_id);

-- status 'ativo' (nao o 'contactado' do dcbo-backend): todos os leads existentes vieram do site, e
-- 'ativo' e o que o site sempre escreveu (dc/src/services/firebaseService.js) e o que
-- LeadService#createFromWebsite grava. 'contactado' afirmaria um contacto que nao aconteceu. O
-- default fica em 'ativo' tambem para a instancia antiga durante o deploy (o Lead antigo nao
-- mapeia status; a BD preenche).
-- leads_origem_check e o nome que o PostgreSQL deu ao CHECK inline de V1; alarga-se a 'backoffice'.
ALTER TABLE leads
    ADD COLUMN notas           TEXT,
    ADD COLUMN carro_preco     NUMERIC(12,2),
    ADD COLUMN follow_up_date  DATE,
    ADD COLUMN status          VARCHAR(50) NOT NULL DEFAULT 'ativo'
        CONSTRAINT leads_status_check
        CHECK (status IN ('ativo', 'contactado', 'test_drive_marcado', 'test_drive_realizado',
                          'proposta_feita', 'negociacao', 'vendido', 'desistiu')),
    ADD COLUMN updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT leads_car_id_fkey FOREIGN KEY (car_id) REFERENCES cars (id) ON DELETE SET NULL,
    DROP CONSTRAINT leads_origem_check,
    ADD CONSTRAINT leads_origem_check CHECK (origem IN ('website', 'website-contacto', 'backoffice'));

-- Um lead anterior a esta migracao nunca foi editado.
UPDATE leads SET updated_at = created_at;

CREATE INDEX idx_leads_status_created_at ON leads (status, created_at DESC);
CREATE INDEX idx_leads_follow_up_date ON leads (follow_up_date) WHERE follow_up_date IS NOT NULL;
-- Serve o ON DELETE SET NULL ao apagar um carro e o filtro por carro do back-office.
CREATE INDEX idx_leads_car_id ON leads (car_id);

-- forwarded_at, forward_attempts e idx_leads_forwarded_at ficam: o encaminhamento para o
-- dcbo-backend existe ate a TASK-006, cuja migracao de limpeza os remove (junto com
-- cars.synced_at, que deixou de ter escritor). cars.observacoes fica e continua publico.

-- 6. Notificacoes e utilizadores do back-office (identicas ao dcbo-backend V1).

CREATE TABLE notifications (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tipo              VARCHAR(100) NOT NULL,
    titulo            VARCHAR(255),
    mensagem          TEXT NOT NULL,
    prioridade        VARCHAR(20),
    lead_id           UUID REFERENCES leads (id) ON DELETE CASCADE,
    read              BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notifications_lead_id ON notifications (lead_id);

CREATE TABLE app_users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_subject      VARCHAR(255) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    name              VARCHAR(255) NOT NULL,
    role              VARCHAR(50) NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT true,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_app_users_auth_subject ON app_users (auth_subject);
