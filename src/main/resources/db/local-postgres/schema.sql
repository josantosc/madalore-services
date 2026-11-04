CREATE SCHEMA IF NOT EXISTS tasy_test;
SET search_path TO tasy_test;

CREATE SEQUENCE IF NOT EXISTS pessoa_fisica_seq START WITH 10000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS agenda_consulta_seq START WITH 10000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS agenda_consulta_proc_seq START WITH 10000 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS agenda_paciente_seq START WITH 10000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS pessoa_fisica (
    cd_pessoa_fisica VARCHAR(40) PRIMARY KEY,
    ie_tipo_pessoa NUMERIC,
    nm_pessoa_fisica VARCHAR(240),
    dt_atualizacao TIMESTAMP,
    nm_usuario VARCHAR(60),
    dt_nascimento TIMESTAMP,
    ie_sexo VARCHAR(4),
    nr_cpf VARCHAR(44),
    nr_telefone_celular VARCHAR(160),
    ds_codigo_prof VARCHAR(60),
    nr_seq_conselho NUMERIC
);

CREATE TABLE IF NOT EXISTS especialidade_medica (
    cd_especialidade NUMERIC PRIMARY KEY,
    ds_especialidade VARCHAR(400) NOT NULL,
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    ie_situacao VARCHAR(4) NOT NULL DEFAULT 'A'
);

CREATE TABLE IF NOT EXISTS convenio (
    cd_convenio NUMERIC PRIMARY KEY,
    ds_convenio VARCHAR(1020) NOT NULL,
    dt_inclusao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ie_tipo_convenio NUMERIC NOT NULL DEFAULT 1,
    ie_situacao VARCHAR(4) NOT NULL DEFAULT 'A',
    cd_cgc VARCHAR(56) NOT NULL DEFAULT '00000000000000',
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    dt_dia_vencimento NUMERIC NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS agenda (
    cd_agenda NUMERIC PRIMARY KEY,
    cd_estabelecimento NUMERIC NOT NULL DEFAULT 1,
    ds_agenda VARCHAR(200) NOT NULL,
    cd_tipo_agenda NUMERIC NOT NULL,
    cd_pessoa_fisica VARCHAR(40),
    cd_especialidade NUMERIC,
    cd_agenda_externa VARCHAR(80),
    ie_situacao VARCHAR(4) NOT NULL DEFAULT 'A',
    ie_feriado VARCHAR(4) NOT NULL DEFAULT 'N',
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    qt_dia_historico NUMERIC NOT NULL DEFAULT 0,
    ie_medico_executor VARCHAR(4) NOT NULL DEFAULT 'N',
    ie_gerar_sobra_horario VARCHAR(4) NOT NULL DEFAULT 'N',
    hr_quebra_turno VARCHAR(8) NOT NULL DEFAULT '00:00',
    qt_min_quebra_turno VARCHAR(8) NOT NULL DEFAULT '0',
    ie_gerar_solic_pront VARCHAR(4) NOT NULL DEFAULT 'N',
    ie_tipo_agenda_consulta VARCHAR(8) NOT NULL DEFAULT 'N',
    ie_gerar_solic_pront_gp VARCHAR(4) NOT NULL DEFAULT 'N',
    ie_medico_requisitante VARCHAR(4) NOT NULL DEFAULT 'N',
    CONSTRAINT fk_agenda_profissional FOREIGN KEY (cd_pessoa_fisica)
        REFERENCES pessoa_fisica (cd_pessoa_fisica),
    CONSTRAINT fk_agenda_especialidade FOREIGN KEY (cd_especialidade)
        REFERENCES especialidade_medica (cd_especialidade)
);

CREATE TABLE IF NOT EXISTS agenda_consulta (
    nr_sequencia NUMERIC PRIMARY KEY DEFAULT nextval('tasy_test.agenda_consulta_seq'),
    cd_agenda NUMERIC NOT NULL,
    dt_agenda TIMESTAMP NOT NULL,
    nr_minuto_duracao NUMERIC NOT NULL DEFAULT 30,
    ie_status_agenda VARCHAR(8) NOT NULL DEFAULT 'L',
    ie_classif_agenda VARCHAR(20) NOT NULL DEFAULT 'N',
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    cd_convenio NUMERIC,
    cd_pessoa_fisica VARCHAR(40),
    nm_paciente VARCHAR(320),
    dt_nascimento_pac TIMESTAMP,
    cd_turno VARCHAR(4) NOT NULL DEFAULT 'D',
    dt_agendamento TIMESTAMP,
    dt_confirmacao TIMESTAMP,
    dt_cancelamento TIMESTAMP,
    cd_medico VARCHAR(40),
    cd_procedimento NUMERIC,
    ie_origem_proced NUMERIC,
    nr_seq_proc_interno NUMERIC,
    cd_categoria VARCHAR(40),
    cd_plano VARCHAR(40),
    cd_especialidade NUMERIC,
    CONSTRAINT fk_agecons_agenda FOREIGN KEY (cd_agenda) REFERENCES agenda (cd_agenda),
    CONSTRAINT fk_agecons_paciente FOREIGN KEY (cd_pessoa_fisica)
        REFERENCES pessoa_fisica (cd_pessoa_fisica),
    CONSTRAINT fk_agecons_convenio FOREIGN KEY (cd_convenio) REFERENCES convenio (cd_convenio)
);

ALTER TABLE agenda_consulta ADD COLUMN IF NOT EXISTS dt_confirmacao TIMESTAMP;
ALTER TABLE agenda_consulta ADD COLUMN IF NOT EXISTS dt_cancelamento TIMESTAMP;

CREATE TABLE IF NOT EXISTS agenda_paciente (
    nr_sequencia NUMERIC PRIMARY KEY DEFAULT nextval('tasy_test.agenda_paciente_seq'),
    cd_agenda NUMERIC NOT NULL,
    cd_pessoa_fisica VARCHAR(40),
    dt_agenda TIMESTAMP NOT NULL,
    hr_inicio TIMESTAMP NOT NULL,
    nr_minuto_duracao NUMERIC NOT NULL DEFAULT 30,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cd_medico VARCHAR(40),
    cd_procedimento NUMERIC,
    ie_origem_proced NUMERIC,
    ie_status_agenda VARCHAR(12) DEFAULT 'L',
    nm_paciente VARCHAR(240),
    cd_convenio NUMERIC,
    cd_categoria VARCHAR(40),
    cd_plano VARCHAR(40),
    nr_seq_proc_interno NUMERIC,
    dt_nascimento_pac TIMESTAMP,
    ie_equipamento VARCHAR(4) NOT NULL DEFAULT 'N',
    CONSTRAINT fk_agepaci_agenda FOREIGN KEY (cd_agenda) REFERENCES agenda (cd_agenda),
    CONSTRAINT fk_agepaci_paciente FOREIGN KEY (cd_pessoa_fisica)
        REFERENCES pessoa_fisica (cd_pessoa_fisica),
    CONSTRAINT fk_agepaci_convenio FOREIGN KEY (cd_convenio) REFERENCES convenio (cd_convenio)
);

CREATE TABLE IF NOT EXISTS proc_interno (
    nr_sequencia NUMERIC PRIMARY KEY,
    ds_proc_exame VARCHAR(400),
    cd_procedimento NUMERIC NOT NULL,
    ie_origem_proced NUMERIC NOT NULL,
    cd_integracao VARCHAR(80),
    ie_tipo VARCHAR(20) NOT NULL DEFAULT 'O',
    ie_situacao VARCHAR(4) DEFAULT 'A',
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL'
);

ALTER TABLE proc_interno ADD COLUMN IF NOT EXISTS ie_tipo VARCHAR(20) NOT NULL DEFAULT 'O';

CREATE TABLE IF NOT EXISTS proc_interno_orientacao (
    nr_sequencia NUMERIC PRIMARY KEY,
    cd_estabelecimento NUMERIC,
    nr_seq_proc_interno NUMERIC,
    dt_atualizacao TIMESTAMP,
    nm_usuario VARCHAR(60),
    dt_atualizacao_nrec TIMESTAMP,
    nm_usuario_nrec VARCHAR(60),
    ie_tipo_orientacao VARCHAR(12),
    ds_orientacao TEXT,
    ds_timezone VARCHAR(160),
    CONSTRAINT fk_proc_orient_proc_interno
        FOREIGN KEY (nr_seq_proc_interno) REFERENCES proc_interno (nr_sequencia)
);

CREATE TABLE IF NOT EXISTS agenda_consulta_proc (
    nr_sequencia NUMERIC PRIMARY KEY DEFAULT nextval('tasy_test.agenda_consulta_proc_seq'),
    nr_seq_agenda NUMERIC NOT NULL,
    cd_procedimento NUMERIC NOT NULL,
    ie_origem_proced NUMERIC NOT NULL,
    nr_seq_proc_interno NUMERIC,
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    CONSTRAINT fk_agecons_proc_agenda FOREIGN KEY (nr_seq_agenda)
        REFERENCES agenda_consulta (nr_sequencia)
);

CREATE TABLE IF NOT EXISTS agenda_paciente_proc (
    nr_sequencia NUMERIC NOT NULL,
    nr_seq_agenda NUMERIC NOT NULL,
    cd_procedimento NUMERIC NOT NULL,
    ie_origem_proced NUMERIC NOT NULL,
    nr_seq_proc_interno NUMERIC,
    dt_atualizacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    nm_usuario VARCHAR(60) NOT NULL DEFAULT 'LOCAL',
    PRIMARY KEY (nr_sequencia, nr_seq_agenda),
    CONSTRAINT fk_agepaci_proc_agenda FOREIGN KEY (nr_sequencia)
        REFERENCES agenda_paciente (nr_sequencia)
);

CREATE TABLE IF NOT EXISTS agendamento_operacao (
    chave VARCHAR(300) PRIMARY KEY,
    codigo VARCHAR(60) NOT NULL,
    dt_criacao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_pessoa_cpf_nascimento
    ON pessoa_fisica (nr_cpf, dt_nascimento);
CREATE INDEX IF NOT EXISTS idx_agenda_tipo_situacao
    ON agenda (cd_tipo_agenda, ie_situacao);
CREATE INDEX IF NOT EXISTS idx_agecons_disponibilidade
    ON agenda_consulta (ie_status_agenda, dt_agenda, cd_agenda);
CREATE INDEX IF NOT EXISTS idx_agecons_paciente_data
    ON agenda_consulta (cd_pessoa_fisica, dt_agenda);
CREATE UNIQUE INDEX IF NOT EXISTS uk_agecons_agenda_data
    ON agenda_consulta (cd_agenda, dt_agenda);
CREATE INDEX IF NOT EXISTS idx_agepaci_disponibilidade
    ON agenda_paciente (ie_status_agenda, hr_inicio, cd_agenda);
CREATE INDEX IF NOT EXISTS idx_agepaci_paciente_data
    ON agenda_paciente (cd_pessoa_fisica, hr_inicio);
CREATE UNIQUE INDEX IF NOT EXISTS uk_proc_interno_integracao
    ON proc_interno (cd_procedimento, ie_origem_proced, cd_integracao);
CREATE INDEX IF NOT EXISTS idx_proc_orient_proc_interno
    ON proc_interno_orientacao (nr_seq_proc_interno);

-- Stubs determinísticos das functions consultadas pelos fluxos Tasy.
CREATE OR REPLACE FUNCTION obtem_integra_telemedicina(
    nr_seq_agecons NUMERIC,
    ie_acao VARCHAR
) RETURNS VARCHAR
LANGUAGE SQL
IMMUTABLE
AS 'SELECT ''N''::VARCHAR';

CREATE OR REPLACE FUNCTION consiste_bloq_mesmo_dia_turno(
    cd_agenda NUMERIC,
    dt_agenda TIMESTAMP
) RETURNS VARCHAR
LANGUAGE SQL
IMMUTABLE
AS 'SELECT ''N''::VARCHAR';

CREATE OR REPLACE FUNCTION consiste_bloq_mesmo_dia_turno(
    cd_agenda NUMERIC,
    dt_agenda TIMESTAMP WITH TIME ZONE
) RETURNS VARCHAR
LANGUAGE SQL
IMMUTABLE
AS 'SELECT ''N''::VARCHAR';
