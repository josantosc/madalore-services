CREATE SCHEMA IF NOT EXISTS agenda;

-- Agenda do Mandalore para clínicas sem integração com o sistema delas.
-- conta_id é a conta do Chatwoot: cada clínica vê e altera só as próprias linhas.
-- Horários são locais da clínica (America/Sao_Paulo), sem fuso.

CREATE TABLE IF NOT EXISTS agenda.profissional (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nome VARCHAR(200) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS agenda.especialidade (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nome VARCHAR(200) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS agenda.convenio (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nome VARCHAR(200) NOT NULL,
    ativo BOOLEAN NOT NULL DEFAULT TRUE
);

-- Horário de atendimento. oferece_bot: o bot pode oferecer esses horários ("vagas do bot").
-- dia_semana segue a ISO: 1 = segunda, 7 = domingo.
CREATE TABLE IF NOT EXISTS agenda.grade (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    especialidade_id BIGINT NOT NULL REFERENCES agenda.especialidade (id),
    dia_semana SMALLINT NOT NULL CHECK (dia_semana BETWEEN 1 AND 7),
    inicio TIME NOT NULL,
    fim TIME NOT NULL,
    duracao_min SMALLINT NOT NULL CHECK (duracao_min BETWEEN 5 AND 480),
    oferece_bot BOOLEAN NOT NULL DEFAULT TRUE,
    local VARCHAR(200) NOT NULL DEFAULT '',
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    CHECK (fim > inicio)
);

-- Grade semanal (dia_semana, vigência opcional) ou só numa data (data). Grades antigas viram semanais sem vigência.
ALTER TABLE agenda.grade ALTER COLUMN dia_semana DROP NOT NULL;
ALTER TABLE agenda.grade ADD COLUMN IF NOT EXISTS data DATE;
ALTER TABLE agenda.grade ADD COLUMN IF NOT EXISTS vigencia_inicio DATE;
ALTER TABLE agenda.grade ADD COLUMN IF NOT EXISTS vigencia_fim DATE;
ALTER TABLE agenda.grade DROP CONSTRAINT IF EXISTS grade_recorrencia_check;
ALTER TABLE agenda.grade ADD CONSTRAINT grade_recorrencia_check
    CHECK ((data IS NULL) <> (dia_semana IS NULL)
        AND (vigencia_inicio IS NULL OR vigencia_fim IS NULL OR vigencia_fim >= vigencia_inicio));

-- Período sem atendimento. Sem profissional: vale para a clínica inteira (feriado).
CREATE TABLE IF NOT EXISTS agenda.bloqueio (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    profissional_id BIGINT REFERENCES agenda.profissional (id),
    inicio TIMESTAMP NOT NULL,
    fim TIMESTAMP NOT NULL,
    motivo VARCHAR(300) NOT NULL DEFAULT '',
    criado_por VARCHAR(100),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (fim > inicio)
);

CREATE TABLE IF NOT EXISTS agenda.paciente (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    cpf VARCHAR(11) NOT NULL,
    nascimento DATE NOT NULL,
    nome VARCHAR(240) NOT NULL,
    telefone VARCHAR(40) NOT NULL DEFAULT '',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (conta_id, cpf, nascimento)
);

-- status: MARCADO, CONFIRMADO, CANCELADO, REMARCADO. origem: BOT ou RECEPCAO.
-- codigo_externo: número do agendamento no sistema da clínica, informado ao concluir a pendência.
CREATE TABLE IF NOT EXISTS agenda.agendamento (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    especialidade_id BIGINT NOT NULL REFERENCES agenda.especialidade (id),
    convenio_id BIGINT REFERENCES agenda.convenio (id),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    inicio TIMESTAMP NOT NULL,
    fim TIMESTAMP NOT NULL,
    local VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL CHECK (status IN ('MARCADO', 'CONFIRMADO', 'CANCELADO', 'REMARCADO')),
    origem VARCHAR(20) NOT NULL CHECK (origem IN ('BOT', 'RECEPCAO')),
    codigo_externo VARCHAR(100),
    conversa_id VARCHAR(40),
    remarcado_de BIGINT REFERENCES agenda.agendamento (id),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Dois agendamentos ativos no mesmo horário do mesmo profissional são impossíveis.
CREATE UNIQUE INDEX IF NOT EXISTS uk_agendamento_ativo
    ON agenda.agendamento (conta_id, profissional_id, inicio)
    WHERE status IN ('MARCADO', 'CONFIRMADO');
CREATE INDEX IF NOT EXISTS idx_agendamento_periodo ON agenda.agendamento (conta_id, inicio);
CREATE INDEX IF NOT EXISTS idx_agendamento_paciente ON agenda.agendamento (paciente_id, inicio);

CREATE TABLE IF NOT EXISTS agenda.agendamento_evento (
    id BIGSERIAL PRIMARY KEY,
    agendamento_id BIGINT NOT NULL REFERENCES agenda.agendamento (id),
    tipo VARCHAR(30) NOT NULL,
    usuario VARCHAR(100) NOT NULL,
    detalhe VARCHAR(1000) NOT NULL DEFAULT '',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_agendamento_evento ON agenda.agendamento_evento (agendamento_id, criado_em);

-- O que falta fazer no sistema da clínica. Quando houver integração, o adaptador consome esta fila.
-- tipo: LANCAR, CANCELAR, REMARCAR, CONFIRMAR, CONFERIR. situacao: ABERTA, CONCLUIDA, DESCARTADA.
CREATE TABLE IF NOT EXISTS agenda.pendencia (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    agendamento_id BIGINT NOT NULL REFERENCES agenda.agendamento (id),
    tipo VARCHAR(20) NOT NULL CHECK (tipo IN ('LANCAR', 'CANCELAR', 'REMARCAR', 'CONFIRMAR', 'CONFERIR')),
    situacao VARCHAR(20) NOT NULL DEFAULT 'ABERTA' CHECK (situacao IN ('ABERTA', 'CONCLUIDA', 'DESCARTADA')),
    resumo VARCHAR(2000) NOT NULL,
    prazo TIMESTAMP NOT NULL,
    criada_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    concluida_em TIMESTAMPTZ,
    concluida_por VARCHAR(100),
    codigo_externo VARCHAR(100),
    observacao VARCHAR(500) NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_pendencia_aberta ON agenda.pendencia (conta_id, situacao, prazo);

-- Conferência diária: a recepção compara a agenda do Mandalore com a do sistema da clínica.
CREATE TABLE IF NOT EXISTS agenda.conferencia (
    agendamento_id BIGINT PRIMARY KEY REFERENCES agenda.agendamento (id),
    situacao VARCHAR(20) NOT NULL CHECK (situacao IN ('CONFERIDO', 'DIVERGENTE')),
    observacao VARCHAR(500) NOT NULL DEFAULT '',
    usuario VARCHAR(100) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Resultado de cada operação do bot pela chave de idempotência: repetir a mensagem não repete a gravação.
CREATE TABLE IF NOT EXISTS agenda.operacao (
    chave VARCHAR(300) PRIMARY KEY,
    codigo VARCHAR(60) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
