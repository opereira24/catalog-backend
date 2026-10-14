-- V3__drop_lead_forwarding.sql
-- Fim do encaminhamento de leads para o dcbo-backend (TASK-006). POST /api/leads grava o lead e a
-- notificacao do back-office na mesma transacao, sem chamar outro servico; as colunas que so
-- serviam o encaminhamento (e o cars.synced_at, sem escritor desde a V2) saem aqui.
--
-- Regras desta migracao:
--   * Nenhuma linha e apagada nem alterada: so DROP COLUMN. Os leads que nunca foram encaminhados
--     (forwarded_at nulo, ate 5 tentativas) ficam como leads normais e aparecem na listagem do
--     back-office. V2ToV3UpgradeTest compara todas as colunas que sobrevivem, linha a linha.
--   * Corre durante um deploy sem interrupcao no Render: a instancia antiga continua a servir (e o
--     LeadForwardScheduler dela a ler leads) enquanto esta corre. Por isso as tres linhas de
--     cabeca abaixo, provadas por V3LockTest. Convencao para qualquer migracao que altere tabelas
--     existentes: ver domain/CLAUDE.md.
--   * Como todas as migracoes no PostgreSQL, corre numa transacao: ou sai tudo ou nada.

-- SET LOCAL, nunca SET: o valor acaba no commit/rollback desta migracao e nao fica numa ligacao
-- do pool. Com uma transacao aberta a ler leads ou cars, a migracao falha ao fim de 10 s com
-- 55P03 (lock timeout), as colunas continuam la, o arranque falha e o Render mantem a instancia
-- antiga. Sem isto a migracao esperava sem limite e todas as leituras dessas tabelas ficavam em
-- fila atras do ACCESS EXCLUSIVE dela.
SET LOCAL lock_timeout = '10s';

-- Um so LOCK TABLE, com cars antes de leads: e a ordem da aplicacao (POST /api/leads pede FOR KEY
-- SHARE ao carro e depois insere em leads; o DELETE de um carro tambem tranca cars e depois
-- atualiza leads). Com leads primeiro (ou com os ALTER pela ordem leads, cars), um lead a meio da
-- transacao dava "deadlock detected" e a vitima medida foi o INSERT do lead do visitante.
LOCK TABLE cars, leads IN ACCESS EXCLUSIVE MODE;

ALTER TABLE cars DROP COLUMN synced_at;

-- idx_leads_forwarded_at sai com a coluna.
ALTER TABLE leads DROP COLUMN forwarded_at, DROP COLUMN forward_attempts;
