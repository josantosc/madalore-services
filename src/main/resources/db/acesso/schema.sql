CREATE SCHEMA IF NOT EXISTS acesso;

-- Usuários da central /admin. Senha só como hash BCrypt.
-- versao: aumenta a cada alteração de acesso ou senha; a sessão aberta numa versão antiga deixa de valer.
-- trocar_senha: senha provisória, o usuário troca no primeiro acesso.
CREATE TABLE IF NOT EXISTS acesso.usuario (
    id BIGSERIAL PRIMARY KEY,
    nome VARCHAR(200) NOT NULL,
    email VARCHAR(200) NOT NULL,
    senha_hash VARCHAR(100) NOT NULL,
    perfil VARCHAR(20) NOT NULL
        CHECK (perfil IN ('ADMINISTRADOR', 'GESTOR', 'RECEPCAO', 'FATURAMENTO', 'CURADORIA')),
    ativo BOOLEAN NOT NULL DEFAULT TRUE,
    trocar_senha BOOLEAN NOT NULL DEFAULT TRUE,
    versao INTEGER NOT NULL DEFAULT 1,
    criado_por VARCHAR(200) NOT NULL,
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ultimo_acesso_em TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_usuario_email ON acesso.usuario (lower(email));

-- Clínicas (conta do Chatwoot) que o usuário enxerga. O administrador geral não tem linha: vê todas.
CREATE TABLE IF NOT EXISTS acesso.usuario_conta (
    usuario_id BIGINT NOT NULL REFERENCES acesso.usuario (id),
    conta_id VARCHAR(40) NOT NULL,
    PRIMARY KEY (usuario_id, conta_id)
);
CREATE INDEX IF NOT EXISTS idx_usuario_conta ON acesso.usuario_conta (conta_id);

-- Ajuste de telas em relação ao perfil: concedido=true acrescenta, false retira.
CREATE TABLE IF NOT EXISTS acesso.usuario_modulo (
    usuario_id BIGINT NOT NULL REFERENCES acesso.usuario (id),
    modulo VARCHAR(30) NOT NULL,
    concedido BOOLEAN NOT NULL,
    PRIMARY KEY (usuario_id, modulo)
);

-- Auditoria de acesso. alvo: e-mail do usuário afetado (ou o que foi digitado no login recusado).
CREATE TABLE IF NOT EXISTS acesso.evento (
    id BIGSERIAL PRIMARY KEY,
    tipo VARCHAR(30) NOT NULL,
    usuario_id BIGINT REFERENCES acesso.usuario (id),
    alvo VARCHAR(200) NOT NULL DEFAULT '',
    autor VARCHAR(200) NOT NULL,
    ip VARCHAR(64),
    criado_em TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_acesso_evento ON acesso.evento (criado_em);
CREATE INDEX IF NOT EXISTS idx_acesso_evento_usuario ON acesso.evento (usuario_id, criado_em);
