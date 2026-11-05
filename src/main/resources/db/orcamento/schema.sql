CREATE SCHEMA IF NOT EXISTS orcamento;

-- Tabela de preços particular, mantida pela tela /precos (ou importação do CSV).
-- sinonimos: nomes alternativos e siglas separados por "|".
CREATE TABLE IF NOT EXISTS orcamento.item_preco (
    codigo VARCHAR(60) PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    sinonimos VARCHAR(1000) NOT NULL DEFAULT '',
    valor NUMERIC(10, 2) NOT NULL CHECK (valor >= 0),
    prazo VARCHAR(100) NOT NULL DEFAULT '',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE orcamento.item_preco ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);
