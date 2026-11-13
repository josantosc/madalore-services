CREATE SCHEMA IF NOT EXISTS atendimento;

-- Vínculo do usuário médico com o profissional da agenda e dados do conselho (NR_CRM/UF_CRM do Tasy).
ALTER TABLE agenda.profissional ADD COLUMN IF NOT EXISTS usuario_id BIGINT REFERENCES acesso.usuario (id);
ALTER TABLE agenda.profissional ADD COLUMN IF NOT EXISTS conselho_numero VARCHAR(30);
ALTER TABLE agenda.profissional ADD COLUMN IF NOT EXISTS conselho_uf CHAR(2);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profissional_usuario
    ON agenda.profissional (usuario_id) WHERE usuario_id IS NOT NULL;

-- ATENDIMENTO_PACIENTE (ie_tipo_atendimento 8 = ambulatorial)
CREATE TABLE IF NOT EXISTS atendimento.atendimento_paciente (
    nr_atendimento BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    agendamento_id BIGINT NOT NULL REFERENCES agenda.agendamento (id),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    especialidade_id BIGINT NOT NULL REFERENCES agenda.especialidade (id),
    convenio_id BIGINT REFERENCES agenda.convenio (id),
    ie_tipo_atendimento SMALLINT NOT NULL DEFAULT 8,
    ie_status_atendimento VARCHAR(20) NOT NULL
        CHECK (ie_status_atendimento IN ('AGUARDANDO', 'EM_CONSULTA', 'FINALIZADO', 'CANCELADO')),
    dt_entrada TIMESTAMP NOT NULL,
    dt_atend_medico TIMESTAMP,
    dt_fim_consulta TIMESTAMP,
    dt_alta TIMESTAMP,
    ie_motivo_alta VARCHAR(20) CHECK (ie_motivo_alta IN ('ALTA_MEDICA', 'ENCAMINHADO', 'DESISTENCIA', 'OUTRO')),
    nm_usuario_entrada VARCHAR(100) NOT NULL,
    nm_usuario_atend VARCHAR(100),
    nm_usuario_alta VARCHAR(100),
    dt_cancelamento TIMESTAMP,
    nm_usuario_cancelamento VARCHAR(100),
    ds_motivo_cancelamento VARCHAR(300),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ie_status_atendimento <> 'EM_CONSULTA' OR dt_atend_medico IS NOT NULL),
    CHECK (ie_status_atendimento <> 'FINALIZADO' OR (dt_alta IS NOT NULL AND ie_motivo_alta IS NOT NULL)),
    CHECK (ie_status_atendimento <> 'CANCELADO' OR (dt_cancelamento IS NOT NULL AND ds_motivo_cancelamento IS NOT NULL))
);
-- Um atendimento ativo por agendamento: segura o duplo clique e a corrida entre duas recepções.
CREATE UNIQUE INDEX IF NOT EXISTS uk_atendimento_agendamento
    ON atendimento.atendimento_paciente (agendamento_id) WHERE ie_status_atendimento <> 'CANCELADO';
-- Um profissional não atende dois pacientes ao mesmo tempo.
CREATE UNIQUE INDEX IF NOT EXISTS uk_atendimento_profissional_em_consulta
    ON atendimento.atendimento_paciente (profissional_id) WHERE ie_status_atendimento = 'EM_CONSULTA';
CREATE INDEX IF NOT EXISTS idx_atendimento_conta_entrada
    ON atendimento.atendimento_paciente (conta_id, dt_entrada);
CREATE INDEX IF NOT EXISTS idx_atendimento_paciente
    ON atendimento.atendimento_paciente (conta_id, paciente_id, dt_entrada DESC);
-- Chamada do paciente na sala de espera. O status continua AGUARDANDO até o médico iniciar.
ALTER TABLE atendimento.atendimento_paciente ADD COLUMN IF NOT EXISTS dt_chamada TIMESTAMP;
ALTER TABLE atendimento.atendimento_paciente ADD COLUMN IF NOT EXISTS nm_usuario_chamada VARCHAR(100);

-- AGENDA_CONS_LOG_STATUS / ATEND_PACIENTE_HIST. Nunca guarda conteúdo clínico.
CREATE TABLE IF NOT EXISTS atendimento.atendimento_evento (
    id BIGSERIAL PRIMARY KEY,
    nr_atendimento BIGINT NOT NULL REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    tipo VARCHAR(30) NOT NULL,
    usuario VARCHAR(100) NOT NULL,
    detalhe VARCHAR(300) NOT NULL DEFAULT '',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_atendimento_evento ON atendimento.atendimento_evento (nr_atendimento, criado_em);

-- ANAMNESE_PACIENTE
CREATE TABLE IF NOT EXISTS atendimento.anamnese_paciente (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nr_atendimento BIGINT NOT NULL REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    ds_queixa_principal TEXT NOT NULL DEFAULT '',
    ds_historia_doenca_atual TEXT NOT NULL DEFAULT '',
    ds_antecedentes_pessoais TEXT NOT NULL DEFAULT '',
    ds_antecedentes_familiares TEXT NOT NULL DEFAULT '',
    ds_medicamentos_uso TEXT NOT NULL DEFAULT '',
    ds_alergias TEXT NOT NULL DEFAULT '',
    ds_habitos TEXT NOT NULL DEFAULT '',
    ds_exame_fisico TEXT NOT NULL DEFAULT '',
    dt_registro TIMESTAMP NOT NULL,
    dt_liberacao TIMESTAMP,
    ie_situacao CHAR(1) NOT NULL DEFAULT 'A' CHECK (ie_situacao IN ('A', 'I')),
    dt_inativacao TIMESTAMP,
    nm_usuario_inativacao VARCHAR(100),
    ds_justificativa VARCHAR(500),
    nm_usuario VARCHAR(100) NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ie_situacao = 'A' OR (dt_inativacao IS NOT NULL AND ds_justificativa IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_anamnese_ativa
    ON atendimento.anamnese_paciente (nr_atendimento) WHERE ie_situacao = 'A';
-- Nota SOAP: avaliação (A) e conduta (P) completam subjetivo e objetivo da anamnese.
ALTER TABLE atendimento.anamnese_paciente ADD COLUMN IF NOT EXISTS ds_avaliacao TEXT NOT NULL DEFAULT '';
ALTER TABLE atendimento.anamnese_paciente ADD COLUMN IF NOT EXISTS ds_conduta TEXT NOT NULL DEFAULT '';

-- PACIENTE_ALERGIA: alergia é do paciente e vale para todas as consultas. Correção por inativação com justificativa.
CREATE TABLE IF NOT EXISTS atendimento.alergia_paciente (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    nr_atendimento BIGINT NOT NULL REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    ds_alergia VARCHAR(200) NOT NULL CHECK (btrim(ds_alergia) <> ''),
    dt_registro TIMESTAMP NOT NULL,
    nm_usuario VARCHAR(100) NOT NULL,
    ie_situacao CHAR(1) NOT NULL DEFAULT 'A' CHECK (ie_situacao IN ('A', 'I')),
    dt_inativacao TIMESTAMP,
    nm_usuario_inativacao VARCHAR(100),
    ds_justificativa VARCHAR(500),
    CHECK (ie_situacao = 'A' OR (dt_inativacao IS NOT NULL AND ds_justificativa IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_alergia_ativa
    ON atendimento.alergia_paciente (conta_id, paciente_id, lower(btrim(ds_alergia))) WHERE ie_situacao = 'A';

-- EVOLUCAO_PACIENTE (cd_evolucao, dt_evolucao, ds_evolucao, dt_liberacao, ie_situacao...)
CREATE TABLE IF NOT EXISTS atendimento.evolucao_paciente (
    cd_evolucao BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nr_atendimento BIGINT NOT NULL REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    ds_evolucao TEXT NOT NULL DEFAULT '',
    qt_peso NUMERIC(6, 2),
    qt_altura_cm NUMERIC(5, 1),
    dt_retorno_prev DATE,
    dt_evolucao TIMESTAMP NOT NULL,
    dt_liberacao TIMESTAMP,
    ie_situacao CHAR(1) NOT NULL DEFAULT 'A' CHECK (ie_situacao IN ('A', 'I')),
    dt_inativacao TIMESTAMP,
    nm_usuario_inativacao VARCHAR(100),
    ds_justificativa VARCHAR(500),
    nm_usuario VARCHAR(100) NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ie_situacao = 'A' OR (dt_inativacao IS NOT NULL AND ds_justificativa IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_evolucao_atendimento ON atendimento.evolucao_paciente (nr_atendimento, dt_evolucao);

-- DIAGNOSTICO_DOENCA (cd_doenca = CID)
CREATE TABLE IF NOT EXISTS atendimento.diagnostico_doenca (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nr_atendimento BIGINT NOT NULL REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    cd_doenca VARCHAR(10) NOT NULL CHECK (cd_doenca ~ '^[A-Z][0-9]{2}(\.[0-9A-Z]{1,2})?$'),
    ds_diagnostico VARCHAR(500) NOT NULL DEFAULT '',
    ie_tipo_diagnostico VARCHAR(12) NOT NULL CHECK (ie_tipo_diagnostico IN ('PRINCIPAL', 'SECUNDARIO')),
    dt_diagnostico TIMESTAMP NOT NULL,
    ie_situacao CHAR(1) NOT NULL DEFAULT 'A' CHECK (ie_situacao IN ('A', 'I')),
    dt_inativacao TIMESTAMP,
    nm_usuario_inativacao VARCHAR(100),
    ds_justificativa VARCHAR(500),
    nm_usuario VARCHAR(100) NOT NULL,
    CHECK (ie_situacao = 'A' OR (dt_inativacao IS NOT NULL AND ds_justificativa IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_diagnostico_principal
    ON atendimento.diagnostico_doenca (nr_atendimento) WHERE ie_tipo_diagnostico = 'PRINCIPAL' AND ie_situacao = 'A';
CREATE UNIQUE INDEX IF NOT EXISTS uk_diagnostico_cid
    ON atendimento.diagnostico_doenca (nr_atendimento, cd_doenca) WHERE ie_situacao = 'A';

-- MED_RECEITA + itens (estilo FA_RECEITA_FARMACIA_ITEM)
CREATE TABLE IF NOT EXISTS atendimento.med_receita (
    nr_sequencia BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nr_atendimento BIGINT NOT NULL REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    ie_tipo_receita VARCHAR(12) NOT NULL DEFAULT 'COMUM' CHECK (ie_tipo_receita IN ('COMUM', 'ESPECIAL')),
    ds_observacao VARCHAR(1000) NOT NULL DEFAULT '',
    dt_receita TIMESTAMP NOT NULL,
    dt_liberacao TIMESTAMP,
    dt_validade DATE,
    ie_situacao CHAR(1) NOT NULL DEFAULT 'A' CHECK (ie_situacao IN ('A', 'I')),
    dt_inativacao TIMESTAMP,
    nm_usuario_inativacao VARCHAR(100),
    ds_justificativa VARCHAR(500),
    nm_usuario VARCHAR(100) NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (ie_situacao = 'A' OR (dt_inativacao IS NOT NULL AND ds_justificativa IS NOT NULL)),
    CHECK (dt_liberacao IS NULL OR dt_validade IS NOT NULL)
);
CREATE INDEX IF NOT EXISTS idx_receita_atendimento ON atendimento.med_receita (nr_atendimento);

CREATE TABLE IF NOT EXISTS atendimento.med_receita_item (
    id BIGSERIAL PRIMARY KEY,
    nr_seq_receita BIGINT NOT NULL REFERENCES atendimento.med_receita (nr_sequencia),
    nr_ordem SMALLINT NOT NULL,
    ds_medicamento VARCHAR(300) NOT NULL,
    ds_apresentacao VARCHAR(200) NOT NULL DEFAULT '',
    ds_posologia VARCHAR(500) NOT NULL,
    ie_via_aplicacao VARCHAR(60) NOT NULL DEFAULT '',
    ds_quantidade VARCHAR(100) NOT NULL DEFAULT '',
    ie_uso_continuo BOOLEAN NOT NULL DEFAULT FALSE,
    ds_observacao VARCHAR(500) NOT NULL DEFAULT '',
    UNIQUE (nr_seq_receita, nr_ordem)
);

-- ATENDIMENTO_ALTA
CREATE TABLE IF NOT EXISTS atendimento.atendimento_alta (
    nr_atendimento BIGINT PRIMARY KEY REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    conta_id VARCHAR(40) NOT NULL,
    ie_desfecho VARCHAR(20) NOT NULL DEFAULT 'ALTA' CHECK (ie_desfecho IN ('ALTA', 'RETORNO', 'ENCAMINHAMENTO')),
    ds_orientacao TEXT NOT NULL DEFAULT '',
    dt_retorno_prev DATE,
    dt_liberacao TIMESTAMP NOT NULL,
    nm_usuario VARCHAR(100) NOT NULL
);

-- PEP_ACESSO: quem leu o prontuário.
CREATE TABLE IF NOT EXISTS atendimento.acesso_prontuario (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    nr_atendimento BIGINT REFERENCES atendimento.atendimento_paciente (nr_atendimento),
    paciente_id BIGINT NOT NULL REFERENCES agenda.paciente (id),
    acao VARCHAR(30) NOT NULL,
    usuario VARCHAR(100) NOT NULL,
    ip VARCHAR(64),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_acesso_prontuario ON atendimento.acesso_prontuario (conta_id, paciente_id, criado_em);

-- Medicamentos favoritos do profissional, para montar a receita com um clique. Não é dado de paciente.
CREATE TABLE IF NOT EXISTS atendimento.medicamento_favorito (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    ds_medicamento VARCHAR(300) NOT NULL CHECK (btrim(ds_medicamento) <> ''),
    ds_apresentacao VARCHAR(200) NOT NULL DEFAULT '',
    ds_posologia VARCHAR(500) NOT NULL DEFAULT '',
    ie_via_aplicacao VARCHAR(60) NOT NULL DEFAULT '',
    ds_quantidade VARCHAR(100) NOT NULL DEFAULT '',
    ie_uso_continuo BOOLEAN NOT NULL DEFAULT FALSE,
    ds_observacao VARCHAR(500) NOT NULL DEFAULT '',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_medicamento_favorito
    ON atendimento.medicamento_favorito (conta_id, profissional_id, lower(btrim(ds_medicamento)),
                                         lower(btrim(ds_apresentacao)), lower(btrim(ds_posologia)));

-- Modelos de texto do profissional. Digitar .atalho num campo da nota expande o texto.
CREATE TABLE IF NOT EXISTS atendimento.modelo_nota (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    profissional_id BIGINT NOT NULL REFERENCES agenda.profissional (id),
    nm_atalho VARCHAR(30) NOT NULL CHECK (nm_atalho ~ '^[a-z0-9_-]{2,30}$'),
    ds_titulo VARCHAR(100) NOT NULL DEFAULT '',
    ds_texto TEXT NOT NULL CHECK (btrim(ds_texto) <> ''),
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_modelo_nota_atalho ON atendimento.modelo_nota (conta_id, profissional_id, nm_atalho);
