# PostgreSQL local para agenda Tasy

O profile `local-postgres` cria um schema reduzido chamado `tasy_test` no
PostgreSQL local. Ele contém somente os objetos necessários para desenvolver
os fluxos de consulta e exame do Mandalore, sem copiar dados clínicos reais.

## Conexão padrão

- Host: `localhost`
- Porta: `5432`
- Database: `postgres`
- Schema: `tasy_test`
- Usuário: `postgres`
- Senha: `postgres`

Os valores podem ser alterados com `POSTGRES_JDBC_URL`, `POSTGRES_USER` e
`POSTGRES_PASSWORD`.

## Inicialização

Execute:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local-postgres"
.\mvnw.cmd spring-boot:run
```

Se o projeto estiver sem Maven Wrapper:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local-postgres"
mvn spring-boot:run
```

Na primeira inicialização, o Spring executa:

- `db/local-postgres/schema.sql`
- `db/local-postgres/data.sql`

Os scripts são idempotentes e recriam horários futuros relativos ao dia da
execução.

## Massa de teste

- Paciente: CPF `52998224725`, nascimento `20/05/1990`
- Médicos: códigos `2001` e `2002`
- Agendas de consulta: `3001` e `3002`
- Agenda de exame: `4001` (`cd_tipo_agenda = 2`), com horários livres `7001` e
  `7002` sem procedimento. Como no Tasy, os exames oferecidos são inferidos das
  marcações anteriores da agenda (`7101` → `5002`, `7102` → `9002`).
- Convênios: `1` e `15`
- Procedimentos de teste: integrações `CONSULTA-TESTE` e `EXAME-TESTE`

## Objetos reproduzidos

Tabelas:

- `PESSOA_FISICA`
- `ESPECIALIDADE_MEDICA`
- `CONVENIO`
- `AGENDA`
- `AGENDA_CONSULTA`
- `AGENDA_CONSULTA_PROC`
- `AGENDA_PACIENTE`
- `AGENDA_PACIENTE_PROC`
- `PROC_INTERNO`

Sequences:

- `PESSOA_FISICA_SEQ`
- `AGENDA_CONSULTA_SEQ`
- `AGENDA_CONSULTA_PROC_SEQ`
- `AGENDA_PACIENTE_SEQ`

Functions locais:

- `OBTEM_INTEGRA_TELEMEDICINA` retorna `N`
- `CONSISTE_BLOQ_MESMO_DIA_TURNO` retorna `N`

## Ciclo do agendamento local

Com o profile `local-postgres`, as operações de escrita deixam de chamar o
`agendamento-ds` e passam a usar o PostgreSQL local:

- consulta de disponibilidade: somente slots com `IE_STATUS_AGENDA = 'L'`;
- agendamento: atualiza atomicamente `L` para `N` e vincula o paciente;
- agendamento sem cadastro: atualiza `L` para `N`, mantém
  `CD_PESSOA_FISICA` nulo e grava `NM_PACIENTE` e `DT_NASCIMENTO_PAC`;
- confirmação: atualiza `N` para `CN`;
- cancelamento: retorna `N`, `CN` ou `M` para `L` e libera o slot;
- remarcação: reserva o novo slot e libera o anterior na mesma transação.

A tabela `AGENDAMENTO_OPERACAO` persiste a chave de idempotência. Repetir a
mesma solicitação não executa a alteração novamente. A condição
`IE_STATUS_AGENDA = 'L'` no `UPDATE` impede dupla reserva concorrente.

Quando CPF e nascimento não encontram uma `PESSOA_FISICA`, o fluxo oferece:

1. agendar sem cadastro, coletando o nome completo;
2. conferir CPF e nascimento novamente.

A primeira opção não insere nem altera `PESSOA_FISICA`. Ela reproduz a
possibilidade nativa da `AGENDA_CONSULTA` de manter um paciente nominal com
`CD_PESSOA_FISICA` nulo.

Essa modalidade está disponível no adapter `local-postgres`. O
`agendamento-ds` atual sempre executa upsert de `PESSOA_FISICA`; por segurança,
o Mandalore recusa o modo sem cadastro fora do profile local até que o contrato
daquela API tenha suporte explícito para essa modalidade.

Ao reiniciar a aplicação, `data.sql` restaura a massa inicial e limpa as
chaves de idempotência, mantendo os testes repetíveis.

## Gravação no Tasy real

Fora do profile local, `AGENDAMENTO_ESCRITA=tasy` grava direto no Oracle do Tasy do cliente
(`TasyAgendamentoAdapter`). O padrão continua `agendamento-ds` até a validação em homologação.

| Operação | Tasy real | Profile local |
|---|---|---|
| Agendar | `L` → `N`, com paciente, convênio escolhido e `NM_USUARIO = TASY_ESCRITA_USUARIO` | Igual, convênio `15` se o horário não tiver |
| Paciente sem `PESSOA_FISICA` | Agenda nominal, sem criar cadastro | Igual |
| Paciente com cadastro | Não altera `PESSOA_FISICA` | Faz upsert em `PESSOA_FISICA` |
| Confirmar | `N`/`CN`/`M` → `CN` | Igual |
| Cancelar | `C`, mantém o paciente no histórico. O horário não volta para a oferta | Volta para `L` e limpa o paciente |
| Remarcar | Reserva o novo e cancela o anterior na mesma transação | Reserva o novo e libera o anterior |
| Idempotência | Em memória, por conta e chave, e conferência do próprio horário | Tabela `AGENDAMENTO_OPERACAO` |

Antes de ligar em um cliente, confirme com a equipe do Tasy: os status usados, se o cancelamento
deve gerar um novo horário livre e se triggers ou procedures do Tasy exigem outras colunas.

As procedures clínicas do Tasy não foram portadas. Reproduzir a lógica real
fora do Tasy produziria resultados incorretos; os objetos locais servem para
testes determinísticos de catálogo, disponibilidade e persistência básica.
