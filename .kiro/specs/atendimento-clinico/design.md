# Design: Atendimento clínico

## 1. Resumo da abordagem

- Novo schema `atendimento` no PostgreSQL da trilha, criado por `db/atendimento/schema.sql` (idempotente), registrado por último em `PostgresObservabilityConfig` porque referencia `acesso.usuario`.
- Os agendamentos continuam em `agenda.agendamento`. O fluxo clínico acrescenta 3 status a ele e cria o atendimento ligado por `agendamento_id`.
- Segue a arquitetura hexagonal atual: `UseCase` (in), `Port` (out), `Service`, `PostgresAtendimentoAdapter` (JDBC com `NamedParameterJdbcTemplate` e `TransactionTemplate`), controllers com `@RequestAttribute(AdminAuthFilter.LOGIN)`.
- Erros de negócio são valores de retorno (`CodigoNegocio`) traduzidos em HTTP 409, como em `AgendaController.operacao`. Argumento inválido vira 400 e falta de permissão vira 403 (`Proibido`).

## 2. Decisões que precisam de confirmação

| # | Decisão adotada na spec | Alternativa |
|---|---|---|
| D1 | Nomes de tabela e campos-chave iguais aos do Tasy (`nr_atendimento`, `dt_entrada`, `dt_atend_medico`...), valores de status em português legível (`AGUARDANDO`). | Seguir só a convenção atual do Mandalore (`inicio`, `criado_em`). Perde a rastreabilidade com o Tasy. |
| D2 | Estender `agenda.agendamento.status` com `AGUARDANDO`, `EM_CONSULTA`, `EXECUTADO`, espelhando `A`/`O`/`E` do Tasy. | Manter o status do agendamento e derivar tudo do atendimento. Evita mexer no índice único, mas a grade e o bot precisam de join em tudo. |
| D3 | Chegada e consulta **não** geram `agenda.pendencia`. | Gerar pendência para lançar no sistema da clínica. Só faz sentido se a clínica mantém o prontuário em outro sistema. |
| D4 | Anamnese em tabela própria com campos por seção. No Tasy ela é um formulário genérico (`MED_AVALIACAO_PACIENTE`) e `ANAMNESE_PACIENTE` está vazia. | Anamnese como evolução do tipo ANAMNESE em texto livre. |
| D5 | Receita estruturada com itens (`med_receita` + `med_receita_item`). No Tasy a receita real é só texto (`MED_RECEITA.DS_RECEITA`). | Só texto livre, como o Tasy. |
| D6 | Motivo da alta como valor fixo (`ALTA_MEDICA`, `ENCAMINHADO`, `DESISTENCIA`, `OUTRO`). | Cadastro `motivo_alta` por clínica (nova tela de cadastros). |
| D7 | Finalizar exige ao menos um registro clínico liberado e nenhum rascunho. | Permitir finalizar sem registro clínico (o Tasy permite). |
| D8 | Perfis separados: recepção não vê conteúdo clínico (LGPD). Dois módulos novos: `ATENDIMENTO` e `CONSULTORIO`. | Um módulo só. |
| D9 | O Mandalore vira o prontuário das clínicas `agenda=mandalore`. A doc atual diz que "a agenda oficial continua no sistema da clínica". Isso muda o posicionamento do produto. | Ficar só no fluxo de fila/chegada sem prontuário. |

## 3. Mapeamento Oracle (Tasy) → PostgreSQL (Mandalore)

| Conceito | Tasy (Oracle) | Mandalore (PostgreSQL) |
|---|---|---|
| Slot/agendamento | `AGENDA_CONSULTA` | `agenda.agendamento` (já existe, sem slot persistido) |
| Status da agenda | `IE_STATUS_AGENDA`: `N`/`CN` → `A` → `O` → `E` | `status`: `MARCADO`/`CONFIRMADO` → `AGUARDANDO` → `EM_CONSULTA` → `EXECUTADO` |
| Chegada | `DT_CHEGADA`, `DT_AGUARDANDO` | `atendimento.dt_entrada` (e evento `CHEGOU`) |
| Início da consulta | `DT_CONSULTA` + `ATENDIMENTO_PACIENTE.DT_ATEND_MEDICO` | `atendimento.dt_atend_medico` (e evento) |
| Fim | `DT_ATENDIDO` + `DT_FIM_CONSULTA` + `DT_ALTA` | `atendimento.dt_fim_consulta`, `dt_alta` (e evento `EXECUTADO`) |
| Atendimento | `ATENDIMENTO_PACIENTE` (tipo 8) | `atendimento.atendimento_paciente` |
| Local do atendimento | `ATEND_PACIENTE_UNIDADE` | `agenda.agendamento.local` (não replicado) |
| Convênio do atendimento | `ATEND_CATEGORIA_CONVENIO` | `atendimento_paciente.convenio_id` |
| Log de status | `AGENDA_CONS_LOG_STATUS`, `AGENDA_CONSULTA_HIST`, `ATEND_PACIENTE_HIST` | `agenda.agendamento_evento` + `atendimento.atendimento_evento` |
| Evolução | `EVOLUCAO_PACIENTE` | `atendimento.evolucao_paciente` |
| Anamnese | `MED_AVALIACAO_PACIENTE` / `ANAMNESE_PACIENTE` | `atendimento.anamnese_paciente` |
| Diagnóstico | `DIAGNOSTICO_DOENCA` | `atendimento.diagnostico_doenca` |
| Receita | `MED_RECEITA` (texto) e `FA_RECEITA_FARMACIA(_ITEM)` | `atendimento.med_receita` + `med_receita_item` |
| Alta | `ATENDIMENTO_ALTA`, `ATENDIMENTO_PACIENTE.DT_ALTA/CD_MOTIVO_ALTA` | `atendimento_paciente` + `atendimento.atendimento_alta` |
| Acesso ao prontuário | `PEP_ACESSO` | `atendimento.acesso_prontuario` |
| Médico | `MEDICO` + `PESSOA_FISICA` | `agenda.profissional` (+ `usuario_id`, `conselho_numero`, `conselho_uf`) |
| Paciente | `PESSOA_FISICA` | `agenda.paciente` |

**Regras do Tasy replicadas:** chegada vincula o atendimento à agenda; iniciar consulta grava `dt_atend_medico` e põe a agenda em `O`; finalizar grava `dt_atendido`/`dt_fim_consulta`/`dt_alta` e põe a agenda em `E`; executar exige atendimento vinculado em agendas de consulta; evolução liberada é imutável e só é corrigida por inativação com justificativa.

**Regras do Tasy não replicadas:** lançamento automático de conta, eventos de SMS, geração de senha/prontuário, regras de convênio, replicação de status entre agendas do mesmo atendimento, integrações (HL7, CATRACA, PFCS).

## 4. Máquina de estados

### 4.1 Agendamento (`agenda.agendamento.status`)

```
MARCADO ──confirmar──► CONFIRMADO
   │                      │
   └──────── chegada ─────┴──► AGUARDANDO ──iniciar──► EM_CONSULTA ──finalizar──► EXECUTADO
                                   │
                                   └── desfazer chegada ──► (volta a MARCADO ou CONFIRMADO)
```

`cancelar` e `remarcar` continuam só a partir de `MARCADO`/`CONFIRMADO`.

### 4.2 Atendimento (`atendimento_paciente.ie_status_atendimento`)

| De | Ação | Para | Quem | Efeito no agendamento |
|---|---|---|---|---|
| (novo) | chegada | `AGUARDANDO` | recepção | `MARCADO`/`CONFIRMADO` → `AGUARDANDO` |
| `AGUARDANDO` | iniciar consulta | `EM_CONSULTA` | médico do atendimento | → `EM_CONSULTA` |
| `AGUARDANDO` | desfazer chegada | `CANCELADO` | recepção | → status anterior |
| `EM_CONSULTA` | finalizar | `FINALIZADO` | médico do atendimento | → `EXECUTADO` |

`FINALIZADO` e `CANCELADO` são finais. O status anterior à chegada é lido do histórico (`agendamento_evento`): `CONFIRMADO` se existe evento `CONFIRMADO`, senão `MARCADO`.

### 4.3 Registros clínicos (anamnese, evolução, receita)

```
RASCUNHO ──liberar──► LIBERADO ──inativar/cancelar (com justificativa)──► INATIVO
```

Representado por `dt_liberacao` (nulo = rascunho) e `ie_situacao` (`A`/`I`), como no Tasy.

## 5. Modelo de dados

### 5.1 Alterações no schema `agenda` (editar `db/agenda/schema.sql`)

```sql
-- status ampliado (a constraint inline do CREATE TABLE também recebe os novos valores)
ALTER TABLE agenda.agendamento DROP CONSTRAINT IF EXISTS agendamento_status_check;
ALTER TABLE agenda.agendamento ADD CONSTRAINT agendamento_status_check
    CHECK (status IN ('MARCADO', 'CONFIRMADO', 'CANCELADO', 'REMARCADO', 'AGUARDANDO', 'EM_CONSULTA', 'EXECUTADO'));

-- o horário fica ocupado em todo o ciclo; troca o índice antigo (que cobria só MARCADO/CONFIRMADO)
CREATE UNIQUE INDEX IF NOT EXISTS uk_agendamento_ocupa_horario
    ON agenda.agendamento (conta_id, profissional_id, inicio)
    WHERE status IN ('MARCADO', 'CONFIRMADO', 'AGUARDANDO', 'EM_CONSULTA', 'EXECUTADO');
DROP INDEX IF EXISTS agenda.uk_agendamento_ativo;
```

O bloco antigo `CREATE UNIQUE INDEX ... uk_agendamento_ativo` deve ser **removido** do arquivo. Se ficar, ele é recriado e apagado a cada subida.

### 5.2 Novo `db/atendimento/schema.sql`

```sql
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
```

Notas:
- Nenhum `ON DELETE CASCADE`. Registro clínico não é apagado.
- A validade da receita vem de `atendimento.receita.validade-dias` (padrão 30).
- `db/acesso/schema.sql` ganha `'MEDICO'` na lista do `usuario_perfil_check` (nas duas ocorrências: o `CREATE TABLE` e o `ADD CONSTRAINT`).

## 6. Camadas e classes

Pacote base `br.com.mandalore.agendamentoconversa`.

### 6.1 Domínio (`application/domain`)

| Arquivo | Conteúdo |
|---|---|
| `model/Atendimento.java` | Classe `final` com enums e records, como `Agenda`. `Status` (AGUARDANDO, EM_CONSULTA, FINALIZADO, CANCELADO), `MotivoAlta`, `Desfecho`, `TipoDiagnostico`, `TipoReceita`. Records: `Atendimento`, `FilaItem`, `Anamnese`, `Evolucao`, `Diagnostico`, `Receita`, `ReceitaItem`, `Alta`, `Evento`, `Prontuario`. |
| `model/Agenda.java` (alterar) | `Status` ganha `AGUARDANDO`, `EM_CONSULTA`, `EXECUTADO`. Novo `ocupaHorario()`. `ativo()` continua `MARCADO`/`CONFIRMADO`. |
| `model/CodigoNegocio.java` (alterar) | Novos códigos listados na seção 8. |
| `port/in/AtendimentoUseCase.java` | Recepção: `fila(conta, data)`, `registrarChegada(conta, agendamentoId, ator)`, `desfazerChegada(conta, atendimentoId, motivo, ator)`. |
| `port/in/ConsultorioUseCase.java` | Médico: `fila`, `iniciarConsulta`, `prontuario`, anamnese, evolução, diagnóstico, receita, `finalizar`, `historicoDoPaciente`. |
| `port/out/AtendimentoPort.java` | Persistência. Todos os métodos recebem `conta`. Operações de transição são transacionais e devolvem `Operacao` (código + id). |
| `service/AtendimentoService.java` | Regras da chegada e da fila. |
| `service/ConsultorioService.java` | Regras do consultório (posse do profissional, estados, validações). |

### 6.2 Adaptadores

| Arquivo | Conteúdo |
|---|---|
| `adapter/out/atendimento/PostgresAtendimentoAdapter.java` | JDBC. `@ConditionalOnProperty` igual ao `PostgresAgendaAdapter` mais `atendimento.enabled`. Usa o bean `observabilityJdbcTemplate` e o `TransactionTemplate` existentes. |
| `adapter/in/web/controller/AtendimentoController.java` | Rotas da recepção (`/atendimento/api/**`). |
| `adapter/in/web/controller/ConsultorioController.java` | Rotas do médico (`/consultorio/api/**`). |
| `adapter/in/web/admin/Modulo.java` (alterar) | `ATENDIMENTO("/atendimento", ...)`, `CONSULTORIO("/consultorio", ...)`. |
| `adapter/in/web/admin/AdminAuthFilter.java` (alterar) | Incluir `/atendimento` e `/consultorio` nos caminhos protegidos (hoje lista `/admin`, `/curadoria`, `/precos`, `/simulador`, `/agenda`, `/funil`, `/usuarios`). |
| `model/Usuario.java` (alterar) | Perfil `MEDICO("Médico", List.of("ATENDIMENTO", "CONSULTORIO"))`. `RECEPCAO` ganha `ATENDIMENTO`. `GESTOR` ganha `ATENDIMENTO` (sem `CONSULTORIO`). |
| `resources/atendimento/index.html`, `resources/consultorio/index.html` | Telas estáticas, no padrão de `agenda/index.html`. |

### 6.3 Pontos existentes que mudam (levantados no código)

Todo código que hoje trata "ativo" como "ocupa o horário" precisa passar a usar `ocupaHorario()` / o novo conjunto SQL. Verificado por busca:

| Arquivo e ponto | Hoje | Passa a ser |
|---|---|---|
| `GradeHorarios.ocupante(...)` (usa `a.status().ativo()`) | horário some da lista livre só com MARCADO/CONFIRMADO | `ocupaHorario()`. Senão o horário de quem chegou aparece livre. |
| `PostgresAgendaAdapter` constante `ATIVO = "status IN ('MARCADO', 'CONFIRMADO')"` (linha 70) | usada nas consultas de horários ocupados | Separar `OCUPA_HORARIO` (5 status) de `ALTERAVEL` (2 status). **Revisar cada uso da constante** e escolher. |
| `PostgresAgendaAdapter` alteração (linha ~417, `.filter(a -> a.status().ativo())`) | confirmar/cancelar/remarcar | Continua `ativo()`. Quando o agendamento existe e está em atendimento, devolver `AGENDAMENTO_EM_ATENDIMENTO` em vez de "não encontrado". |
| `PostgresAgendaAdapter` resumo da conferência (linha ~535, `status IN ('MARCADO','CONFIRMADO')`) | conferência do dia seguinte | Sem mudança. |
| `MandaloreAgendaAdapter` (linha ~89, consultas em aberto do paciente) | `ativo()` e futuro | Sem mudança. Quem já chegou não é "consulta em aberto". |
| `agenda/index.html` | mostra status da grade | Mostrar os 3 status novos (cor e rótulo) e esconder confirmar/cancelar/remarcar para eles. |
| Relatório OpenClaw da agenda | conta pendências e divergências | Sem mudança. Os novos status não geram pendência (D3). |

## 7. API REST

Convenção atual: `?conta=` opcional, resolvida por `login.podeVer(conta)`. Resposta de operação: `200 {codigo, ...}` ou `409 {erro, codigo}` quando `codigo != SUCESSO`. `IllegalArgumentException` → 400, `Proibido` → 403.

### 7.1 Recepção (módulo `ATENDIMENTO`)

| Método e caminho | Corpo | Resposta |
|---|---|---|
| `GET /atendimento/api/fila?data=AAAA-MM-DD` | | `[{agendamentoId, atendimentoId?, horario, paciente, profissional, especialidade, status, chegadaEm?, esperaMin?}]` |
| `POST /atendimento/api/agendamentos/{id}/chegada` | | `{codigo, atendimentoId}` |
| `POST /atendimento/api/atendimentos/{id}/desfazer-chegada` | `{motivo}` | `{codigo}` |
| `GET /atendimento/api/atendimentos/{id}/eventos` | | histórico sem conteúdo clínico |

### 7.2 Consultório (módulo `CONSULTORIO`)

| Método e caminho | Corpo | Resposta |
|---|---|---|
| `GET /consultorio/api/fila?data=` | | só os atendimentos do profissional do usuário |
| `POST /consultorio/api/atendimentos/{id}/iniciar` | | `{codigo}` |
| `GET /consultorio/api/atendimentos/{id}` | | prontuário do atendimento (cabeçalho, anamnese, evoluções, diagnósticos, receitas, alta). Grava auditoria. |
| `PUT /consultorio/api/atendimentos/{id}/anamnese` | campos da anamnese | grava rascunho (cria ou atualiza) |
| `POST /consultorio/api/atendimentos/{id}/anamnese/liberar` | | `{codigo}` |
| `POST /consultorio/api/anamneses/{id}/inativar` | `{justificativa}` | `{codigo}` |
| `POST /consultorio/api/atendimentos/{id}/evolucoes` | `{texto, peso?, alturaCm?, retornoPrevisto?}` | `{codigo, id}` |
| `PUT /consultorio/api/evolucoes/{id}` | idem | só rascunho |
| `POST /consultorio/api/evolucoes/{id}/liberar` | | `{codigo}` |
| `POST /consultorio/api/evolucoes/{id}/inativar` | `{justificativa}` | `{codigo}` |
| `POST /consultorio/api/atendimentos/{id}/diagnosticos` | `{cid, descricao, tipo}` | `{codigo, id}` |
| `POST /consultorio/api/diagnosticos/{id}/inativar` | `{justificativa}` | `{codigo}` |
| `POST /consultorio/api/atendimentos/{id}/receitas` | `{tipo, observacao, itens[]}` | `{codigo, id}` |
| `PUT /consultorio/api/receitas/{id}` | idem | só rascunho |
| `POST /consultorio/api/receitas/{id}/liberar` | | `{codigo}` |
| `POST /consultorio/api/receitas/{id}/cancelar` | `{justificativa}` | `{codigo}` |
| `GET /consultorio/api/receitas/{id}/impressao` | | HTML para imprimir. Grava auditoria. |
| `POST /consultorio/api/atendimentos/{id}/finalizar` | `{motivoAlta, desfecho?, orientacao?, retornoPrevisto?}` | `{codigo}` |
| `GET /consultorio/api/pacientes/{id}/historico` | | atendimentos anteriores do paciente (prontuário). Grava auditoria. |

## 8. Códigos de negócio novos

| Código | Quando | HTTP |
|---|---|---|
| `CHEGADA_FORA_DO_DIA` | agendamento não é de hoje | 409 |
| `STATUS_INVALIDO_PARA_CHEGADA` | status não é `MARCADO`/`CONFIRMADO` | 409 |
| `ATENDIMENTO_EM_ANDAMENTO` | desfazer chegada depois de iniciada a consulta | 409 |
| `STATUS_INVALIDO_PARA_CONSULTA` | iniciar fora de `AGUARDANDO` | 409 |
| `PROFISSIONAL_EM_CONSULTA` | profissional já tem outro atendimento `EM_CONSULTA` | 409 |
| `ATENDIMENTO_NAO_EM_CONSULTA` | gravar registro clínico fora de `EM_CONSULTA` | 409 |
| `REGISTRO_JA_LIBERADO` | editar registro liberado | 409 |
| `REGISTRO_NAO_LIBERADO` | inativar rascunho (rascunho se descarta, não se inativa) | 409 |
| `PROFISSIONAL_SEM_CONSELHO` | liberar receita sem número e UF do conselho | 409 |
| `SEM_REGISTRO_CLINICO` | finalizar sem anamnese/evolução liberada | 409 |
| `RASCUNHO_PENDENTE` | finalizar com rascunho aberto | 409 |
| `AGENDAMENTO_EM_ATENDIMENTO` | confirmar/cancelar/remarcar agendamento já em atendimento | 409 |
| `DIAGNOSTICO_PRINCIPAL_JA_EXISTE` | incluir segundo diagnóstico `PRINCIPAL` ativo (`uk_diagnostico_principal`) | 409 |
| `DIAGNOSTICO_DUPLICADO` | incluir CID já ativo no atendimento (`uk_diagnostico_cid`) | 409 |
| `NAO_ENCONTRADO` | id inexistente ou de outra conta | 409 (padrão atual) |

Falta de vínculo usuário-profissional ou atendimento de outro profissional: 403.

## 9. Fluxos detalhados

### 9.1 Chegada (transação única)

1. `SELECT ... FROM agenda.agendamento WHERE id = :id AND conta_id = :conta FOR UPDATE`.
2. Validar status (`MARCADO`/`CONFIRMADO`) e data (`inicio::date = hoje` no relógio da clínica).
3. `INSERT INTO atendimento.atendimento_paciente (...) VALUES (... 'AGUARDANDO' ...)`. Se violar `uk_atendimento_agendamento`, devolver o atendimento existente (idempotente).
4. `UPDATE agenda.agendamento SET status = 'AGUARDANDO', atualizado_em = now() WHERE id = :id AND status IN ('MARCADO','CONFIRMADO')`. Se afetar 0 linhas, abortar.
5. Gravar `atendimento_evento` (`CHEGADA`) e `agendamento_evento` (`CHEGOU`).

### 9.2 Iniciar consulta

1. Resolver o profissional do usuário (`agenda.profissional.usuario_id = :usuarioId AND conta_id = :conta`). Sem vínculo → 403.
2. Travar o atendimento (`FOR UPDATE`), validar posse (`profissional_id`) e status `AGUARDANDO`.
3. `UPDATE ... SET ie_status_atendimento='EM_CONSULTA', dt_atend_medico=:agora, nm_usuario_atend=:usuario`. O índice `uk_atendimento_profissional_em_consulta` protege o caso do profissional já em consulta (traduzir a violação em `PROFISSIONAL_EM_CONSULTA`).
4. Agendamento → `EM_CONSULTA`. Eventos nas duas tabelas.

### 9.3 Registro clínico

- Toda gravação exige: atendimento da conta, `EM_CONSULTA`, `profissional_id` igual ao do usuário.
- Editar rascunho: `UPDATE ... WHERE id = :id AND dt_liberacao IS NULL AND ie_situacao = 'A'`; 0 linhas → `REGISTRO_JA_LIBERADO`.
- Liberar: valida conteúdo mínimo e faz `UPDATE ... SET dt_liberacao = :agora WHERE dt_liberacao IS NULL`.
- Inativar: só registro liberado; `ie_situacao='I'` com justificativa. A constraint de banco reforça a justificativa.
- Receita liberada: `dt_validade = hoje + validade-dias`. Itens renumerados por `nr_ordem` ao salvar o rascunho (apaga e reinsere os itens do rascunho; itens de receita liberada nunca mudam).

### 9.4 Finalizar

1. Travar atendimento. Validar posse e `EM_CONSULTA`.
2. Contar: anamnese/evolução liberadas ativas (≥ 1) e rascunhos (= 0), incluindo receitas.
3. Inserir `atendimento_alta`. Atualizar o atendimento (`FINALIZADO`, `dt_fim_consulta = dt_alta = :agora`, motivo, usuário).
4. Agendamento → `EXECUTADO`. Eventos nas duas tabelas.

### 9.5 Desfazer chegada

1. Travar atendimento. Exigir `AGUARDANDO`.
2. Marcar `CANCELADO` com motivo e usuário.
3. Agendamento volta a `CONFIRMADO` se existir evento `CONFIRMADO` em `agendamento_evento`, senão `MARCADO`. Eventos nas duas tabelas.

## 10. Segurança e privacidade

- **Autorização em duas camadas:** `AdminAuthFilter` por módulo (caminho) e o service por posse (profissional). O filtro sozinho não basta, pois `CONSULTORIO` pode ser concedido a um perfil sem vínculo.
- **Isolamento:** `conta_id` em toda consulta, e toda busca por id usa `WHERE id = :id AND conta_id = :conta`.
- **Dados sensíveis (LGPD, dado de saúde):** não logar corpo de requisição das rotas `/consultorio/**`. Eventos de histórico guardam só tipo, usuário e um detalhe curto sem texto clínico. O relatório do OpenClaw não inclui conteúdo clínico.
- **Auditoria de leitura:** `GET` de prontuário, histórico e impressão de receita gravam `acesso_prontuario`.
- **Sessão:** reaproveita o cookie e o bloqueio por IP atuais. Considerar expirar a sessão do consultório com inatividade (ponto aberto).
- **Jurídico (validar com a clínica):** guarda e acesso a prontuário eletrônico, validade da receita sem assinatura digital e regras de receita de controle especial. A v1 NÃO emite receita digital válida sem impressão e assinatura manual.

## 11. Propriedades de configuração

| Propriedade | Padrão | Uso |
|---|---|---|
| `atendimento.enabled` | `false` | Liga o fluxo clínico (controllers, adapter e serviços). |
| `atendimento.receita.validade-dias` | `30` | Validade da receita liberada. |
| `mandalore.clientes.<id>.agenda` | já existe | Só `mandalore` habilita o fluxo para a conta. |

## 12. Estratégia de testes

Segue o padrão do projeto (JUnit 5, Mockito, AssertJ; `*IT` com Postgres local e `RUN_LOCAL_POSTGRES_TESTS=true`).

- **Unitários (`*Test`, Mockito):** `AtendimentoServiceTest` e `ConsultorioServiceTest`: transições válidas e inválidas, posse do profissional, validações de liberação, finalização com rascunho/sem registro.
- **Modelo:** `Agenda.Status.ocupaHorario()` x `ativo()` para os 7 status.
- **Regressão da agenda:** `GradeHorarios.ocupante` com agendamento `AGUARDANDO`/`EM_CONSULTA`/`EXECUTADO` não devolve horário livre.
- **Integração (`*IT`):**
  - `PostgresAtendimentoAdapterIT`: ciclo completo (marcar → chegada → iniciar → anamnese → evolução → receita → finalizar) numa conta aleatória `it-<uuid>`.
  - Duas chegadas concorrentes no mesmo agendamento: um atendimento só.
  - Dois profissionais/atendimentos em consulta simultânea; mesmo profissional recusado.
  - Isolamento entre contas (id de outra conta devolve não encontrado).
  - Constraints: liberado sem `dt_validade` é recusado, inativação sem justificativa é recusada.
- **Controller:** perfis (`RECEPCAO` não acessa `/consultorio`; `MEDICO` sem vínculo recebe mensagem), códigos HTTP (200, 400, 403, 409).
- **Migração do schema:** subir duas vezes sobre um banco que já tem o schema antigo e confirmar que o índice antigo some e o novo existe.

## 13. Riscos

| Risco | Mitigação |
|---|---|
| Reuso incorreto de `ativo()` no lugar de `ocupaHorario()` deixa o horário "livre" depois da chegada. | Seção 6.3 como checklist de revisão e testes de regressão. |
| Subir o app novo com status novos no banco e voltar a versão antiga quebra a leitura (`Status.valueOf`). | Deploy sem rollback para versão anterior depois do primeiro atendimento real, ou tratar status desconhecido no mapeamento. |
| `DROP/ADD CONSTRAINT` a cada subida pega lock na tabela `agendamento`. | Mesmo padrão já usado em `grade` e `usuario`. Aceitável pelo volume atual. Reavaliar se a tabela crescer. |
| Mudança de posicionamento do produto (Mandalore vira prontuário). | D9. Alinhar contrato (`docs/contrato-mandalore.md`) e termo de LGPD com as clínicas. |
| Médico sem vínculo bloqueia a clínica no dia de uso. | Checklist de ativação da clínica: vincular usuário e preencher conselho/UF antes de ligar. |
