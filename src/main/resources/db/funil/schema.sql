CREATE SCHEMA IF NOT EXISTS funil;

-- Funil de orçamento (tela /funil). conta_id e conversa_id são do Chatwoot.
-- Sem dado de saúde: nome dos exames, foto e texto da conversa ficam só no Chatwoot.
-- valor e qtd_itens: total e quantidade de exames do orçamento por foto, nulos no orçamento manual.
-- proxima_acao: dia do retorno ao paciente. retorno_avisado_em: dia em que a rotina reabriu a conversa.
CREATE TABLE IF NOT EXISTS funil.oportunidade (
    id BIGSERIAL PRIMARY KEY,
    conta_id VARCHAR(40) NOT NULL,
    conversa_id VARCHAR(40) NOT NULL,
    origem VARCHAR(20) NOT NULL CHECK (origem IN ('ORCAMENTO_FOTO', 'ORCAMENTO_MANUAL')),
    etapa VARCHAR(20) NOT NULL
        CHECK (etapa IN ('NOVO', 'ORCAMENTO_ENVIADO', 'EM_NEGOCIACAO', 'AGENDADO', 'PERDIDO')),
    motivo_perda VARCHAR(30)
        CHECK (motivo_perda IN ('PRECO', 'CONVENIO_NAO_ATENDIDO', 'FEZ_EM_OUTRO_LUGAR', 'SEM_HORARIO', 'DESISTIU',
                                'SEM_RESPOSTA')),
    valor NUMERIC(10, 2) CHECK (valor >= 0),
    qtd_itens SMALLINT CHECK (qtd_itens >= 0),
    responsavel VARCHAR(200),
    proxima_acao DATE,
    retorno_avisado_em DATE,
    ultima_resposta_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    aberta_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizada_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fechada_em TIMESTAMPTZ
);

-- Uma oportunidade aberta por conversa.
CREATE UNIQUE INDEX IF NOT EXISTS uk_oportunidade_aberta
    ON funil.oportunidade (conta_id, conversa_id)
    WHERE etapa IN ('NOVO', 'ORCAMENTO_ENVIADO', 'EM_NEGOCIACAO');
-- Nome do contato no Chatwoot, para a tela. Sem CPF nem telefone.
ALTER TABLE funil.oportunidade ADD COLUMN IF NOT EXISTS paciente VARCHAR(200);

CREATE INDEX IF NOT EXISTS idx_oportunidade_retorno ON funil.oportunidade (etapa, proxima_acao);
CREATE INDEX IF NOT EXISTS idx_oportunidade_fechada ON funil.oportunidade (fechada_em);

CREATE TABLE IF NOT EXISTS funil.evento (
    id BIGSERIAL PRIMARY KEY,
    oportunidade_id BIGINT NOT NULL REFERENCES funil.oportunidade (id),
    etapa_anterior VARCHAR(20),
    etapa VARCHAR(20) NOT NULL,
    usuario VARCHAR(200) NOT NULL,
    detalhe VARCHAR(500) NOT NULL DEFAULT '',
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_funil_evento ON funil.evento (oportunidade_id, criado_em);
