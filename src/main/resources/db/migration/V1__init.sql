-- V1__init.sql
-- Esquema do catalog-backend: uma projecao so de leitura dos carros que o site publico (dc)
-- mostra, mais as submissoes de leads recebidas diretamente do site. Nao e uma replica do
-- modelo completo do back-office (dcbo-backend) -- essa BD e fisicamente separada e guarda
-- campos internos (preco de compra, consignacao, parceiro, cliente) que nunca entram aqui. Ver
-- TASK-013 para a derivacao campo a campo.
--
-- Convencoes: snake_case, TIMESTAMPTZ para instantes, NUMERIC(12,2) para valores monetarios
-- (nunca tipos de virgula flutuante). backlog/CONVENTIONS.md, ADR-001 (fechada 2026-09-10):
-- colunas que guardam o id de outra entidade ficam sempre em ingles (car_id; nunca o equivalente
-- em portugues), atributos de negocio ficam tal como o frontend os escreve hoje -- maioritariamente portugues
-- (marca, preco, vendido, carro_marca, carro_modelo), com clients/partners em ingles no
-- dcbo-backend, mas essas tabelas nao existem aqui.

CREATE TABLE cars (
    id                UUID PRIMARY KEY,
    marca             VARCHAR(100) NOT NULL,
    modelo            VARCHAR(100) NOT NULL,
    ano               INTEGER NOT NULL,
    preco             NUMERIC(12,2) NOT NULL,
    km                INTEGER NOT NULL,
    cor               VARCHAR(50) NOT NULL,
    combustivel       VARCHAR(50) NOT NULL,
    descricao         TEXT,
    observacoes       TEXT,
    garantia_meses    INTEGER NOT NULL DEFAULT 0,
    vendido           BOOLEAN NOT NULL DEFAULT false,
    reservado         BOOLEAN NOT NULL DEFAULT false,
    destaque          BOOLEAN NOT NULL DEFAULT false,
    synced_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_cars_vendido_created_at ON cars (vendido, created_at DESC);
CREATE INDEX idx_cars_destaque ON cars (destaque);

CREATE TABLE car_images (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    car_id            UUID NOT NULL REFERENCES cars (id) ON DELETE CASCADE,
    url               VARCHAR(1000) NOT NULL,
    thumbnail_url     VARCHAR(1000),
    position          INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_car_images_car_id ON car_images (car_id);

CREATE TABLE leads (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    nome              VARCHAR(255) NOT NULL,
    email             VARCHAR(255),
    telefone          VARCHAR(50) NOT NULL,
    mensagem          TEXT,
    car_id            UUID,
    carro_marca       VARCHAR(100),
    carro_modelo      VARCHAR(100),
    origem            VARCHAR(100) NOT NULL DEFAULT 'website'
        CHECK (origem IN ('website', 'website-contacto')),
    forwarded_at      TIMESTAMPTZ,
    forward_attempts  INTEGER NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_leads_forwarded_at ON leads (forwarded_at);
