# Plano de implementação: Atendimento clínico

Ordem pensada para manter a agenda atual funcionando a cada passo. Cada tarefa cita os requisitos de `requirements.md`.

- [x] 1. Ampliar o status do agendamento sem quebrar a agenda (1.1 a 1.5 feitas; falta rodar o `*IT` da subida dupla do schema em Postgres real)
  - [x] 1.1 Editar `db/agenda/schema.sql`: novos valores na constraint de status (no `CREATE TABLE` e no `DROP/ADD CONSTRAINT`), novo índice `uk_agendamento_ocupa_horario`, `DROP INDEX IF EXISTS agenda.uk_agendamento_ativo` e remoção do bloco antigo que cria esse índice. _Requisitos: R13.1, R13.2_
  - [x] 1.2 `Agenda.Status`: adicionar `AGUARDANDO`, `EM_CONSULTA`, `EXECUTADO` e o método `ocupaHorario()`. Manter `ativo()`. _R13.1, R13.2_
  - [x] 1.3 Auditar todos os usos de `ativo()` e da constante `ATIVO` em `PostgresAgendaAdapter` (e demais arquivos listados na seção 6.3 do design). Separar `OCUPA_HORARIO` de `ALTERAVEL`. _R13.2, R13.3, R13.4_
  - [x] 1.4 Ajustar `GradeHorarios.ocupante` para `ocupaHorario()`. _R13.4_
  - [x] 1.5 Devolver `AGENDAMENTO_EM_ATENDIMENTO` ao confirmar/cancelar/remarcar um agendamento em atendimento (novo valor em `CodigoNegocio`). _R13.3_
  - [x] 1.6 Testes: `Agenda.Status` (7 status), `GradeHorarios` com os status novos, subida dupla do schema sobre banco antigo. _R13_

- [x] 2. Criar o schema `atendimento`
  - [x] 2.1 Criar `src/main/resources/db/atendimento/schema.sql` conforme a seção 5.2 do design. _R1–R9, R11, R12_
  - [x] 2.2 Registrar o script por último na lista de `PostgresObservabilityConfig` (depois de `db/acesso/schema.sql`). _R15.3_
  - [x] 2.3 Acrescentar `'MEDICO'` ao `usuario_perfil_check` em `db/acesso/schema.sql` (as duas ocorrências). _R10.1_
  - [x] 2.4 `*IT` de constraints: liberado sem validade, inativação sem justificativa, segundo `PRINCIPAL`, dois atendimentos ativos no mesmo agendamento, dois `EM_CONSULTA` do mesmo profissional. _R7.3, R9, R15.1_

- [x] 3. Acesso, perfil médico e vínculo com o profissional
  - [x] 3.1 `Modulo`: adicionar `ATENDIMENTO("/atendimento")` e `CONSULTORIO("/consultorio")`. _R10_
  - [x] 3.2 `Usuario.Perfil`: criar `MEDICO` (módulos `ATENDIMENTO`, `CONSULTORIO`); acrescentar `ATENDIMENTO` a `RECEPCAO` e `GESTOR`. _R10.1, R10.2_
  - [x] 3.3 `AdminAuthFilter`: proteger `/atendimento` e `/consultorio`. _R10_
  - [x] 3.4 Cadastro de profissional (porta, adapter, controller e `cadastros.html`): campos `usuarioId`, `conselhoNumero`, `conselhoUf`, com validação de um usuário por profissional e do mesmo conta. _R10.3_
  - [x] 3.5 Tela de usuários: oferecer o perfil `MEDICO`. _R10.1_
  - [x] 3.6 Testes: `RECEPCAO` recebe 403 em `/consultorio`; `MEDICO` acessa; módulo concedido por ajuste de telas. _R10.2, R10.5_

- [x] 4. Domínio, portas e serviços
  - [x] 4.1 `Atendimento` (model com enums e records) e novos códigos em `CodigoNegocio` (seção 8 do design). _Todos_
  - [x] 4.2 `AtendimentoUseCase`, `ConsultorioUseCase` e `AtendimentoPort`. _Todos_
  - [x] 4.3 `AtendimentoService` e `ConsultorioService` com as regras (posse do profissional, estados, validações de liberação). _R1, R3–R9_
  - [x] 4.4 Habilitação por clínica: `atendimento.enabled` e `mandalore.clientes.<id>.agenda=mandalore`; rotas devolvem 404 fora disso. _R14_
  - [x] 4.5 Testes unitários dos dois services (Mockito). _R1, R3–R9_

- [x] 5. Chegada, fila e desfazer chegada
  - [x] 5.1 `PostgresAtendimentoAdapter`: `registrarChegada` na transação da seção 9.1 (lock, idempotência, eventos). _R1_
  - [x] 5.2 `fila(conta, data)` da recepção, sem conteúdo clínico, com tempo de espera. _R2.1, R2.2, R2.4_
  - [x] 5.3 `desfazerChegada` com retorno ao status anterior (seção 9.5). _R3_
  - [x] 5.4 `*IT`: chegada, chegada repetida, chegada concorrente, fora do dia, status inválido, desfazer e nova chegada. _R1, R3, R15.1_

- [x] 6. Iniciar consulta
  - [x] 6.1 Resolver profissional do usuário e fila do médico. _R2.3, R10.4_
  - [x] 6.2 `iniciarConsulta` (seção 9.2), traduzindo a violação do índice em `PROFISSIONAL_EM_CONSULTA`. _R4_
  - [x] 6.3 `*IT`: início válido, outro médico (403), status inválido, profissional já em consulta. _R4_

- [x] 7. Anamnese
  - [x] 7.1 Rascunho (criar/atualizar), liberar e inativar com justificativa; no máximo uma ativa. _R5_
  - [x] 7.2 Testes: liberar sem queixa principal, editar liberada, atendimento fora de `EM_CONSULTA`. _R5_

- [x] 8. Evolução
  - [x] 8.1 Criar/editar rascunho, liberar, inativar; várias por atendimento. _R6_
  - [x] 8.2 Testes: texto vazio, edição de liberada, inativação sem justificativa. _R6_

- [x] 9. Diagnóstico (CID)
  - [x] 9.1 Incluir e inativar; validar formato; um principal ativo; sem CID repetido ativo. _R7_
  - [x] 9.2 Testes: formatos válidos e inválidos, segundo principal. _R7_

- [x] 10. Receita
  - [x] 10.1 Rascunho com itens, liberar (validade pela propriedade), cancelar com justificativa. _R8.1–R8.4_
  - [x] 10.2 Exigir conselho e UF do profissional ao liberar. _R8.6_
  - [x] 10.3 Visão HTML para impressão (`GET /consultorio/api/receitas/{id}/impressao`) com dados da clínica, paciente, itens, médico e conselho. _R8.5_ (HTML no service; nome da clínica em `mandalore.clientes.<id>.nome`, senão `admin.nome`; a rota fica na 13.1)
  - [x] 10.4 Testes: sem itens, sem posologia, sem conselho, edição de liberada, impressão de receita em rascunho recusada. _R8_

- [x] 11. Finalizar atendimento
  - [x] 11.1 `finalizar` (seção 9.4): registro clínico mínimo, sem rascunho, alta e desfecho, agendamento `EXECUTADO`. _R9_
  - [x] 11.2 Bloquear qualquer escrita clínica depois de `FINALIZADO`. _R9.6_
  - [x] 11.3 `*IT` do ciclo completo (marcar → chegada → iniciar → anamnese → evolução → CID → receita → finalizar) e confirmação de que o horário continua ocupado. _R9.5, R1–R9_

- [x] 12. Auditoria
  - [x] 12.1 Gravar `acesso_prontuario` nas leituras de prontuário, histórico e impressão. _R8.7, R12.2_
  - [x] 12.2 Endpoint de eventos do atendimento sem conteúdo clínico (gestor e recepção). _R12.1, R12.4_ (use case e adapter prontos; a rota `GET /atendimento/api/atendimentos/{id}/eventos` fica na 13.1)
  - [x] 12.3 Garantir que logs e eventos de histórico não recebem texto clínico (revisão de log e teste). _R15.4_

- [x] 13. Controllers
  - [x] 13.1 `AtendimentoController` (recepção) e `ConsultorioController` (médico) conforme a seção 7 do design, com `@RequestAttribute(AdminAuthFilter.LOGIN)`, resolução de conta e tradução 200/400/403/409. _R10, R11_ (conta não habilitada → 404; prontuário, histórico e impressão com `Cache-Control: no-store`; as rotas de página `GET /atendimento` e `/consultorio` ficam na 14)
  - [x] 13.2 Testes de controller (`MockMvc`): perfis, conta de outra clínica (403/não encontrado), códigos HTTP. _R10, R11_

- [x] 14. Telas
  - [x] 14.1 `resources/atendimento/index.html`: fila do dia, botão de chegada, desfazer, tempo de espera. _R2_ (rota de página `GET /atendimento` em `AtendimentoController`)
  - [x] 14.2 `resources/consultorio/index.html`: fila do médico, iniciar, prontuário (anamnese, evolução, CID, receita), finalizar. _R4–R9_ (rota de página `GET /consultorio` em `ConsultorioController`)
  - [x] 14.3 `agenda/index.html`: rótulos e cores dos novos status; esconder ações de agenda para eles. _R13.3_ (Aguardando âmbar, Em consulta violeta, Atendida cinza; confirmar/remarcar/cancelar só em `MARCADO`/`CONFIRMADO`; eventos `CHEGOU`, `CHEGADA_DESFEITA`, `EM_CONSULTA`, `EXECUTADO` no histórico)
  - [x] 14.4 Link das novas telas na central (`/admin`), respeitando os módulos do usuário. _R10_ (cartões vêm de `/admin/api/eu`, já filtrado por módulo; ícones e dica `ATENDIMENTO_ENABLED` em `admin/index.html`; o menu de `nav.js` já tinha os dois)

- [ ] 15. Documentação e ativação
  - [x] 15.1 Criar `docs/atendimento-clinico.md` (rotina da recepção e do médico, variáveis, checklist de ativação: vincular usuário, preencher conselho/UF, ligar `atendimento.enabled`). _R10, R14_
  - [x] 15.2 Atualizar `docs/agenda-mandalore.md` (novos status), `docs/usuarios-perfis.md` (perfil `MEDICO`, módulos) e `docs/roadmap.md`. _R10, R13_
  - [ ] 15.3 Revisar `docs/contrato-mandalore.md` e o termo de LGPD sobre o Mandalore guardar prontuário (decisão D9). _Risco da seção 13_
  - [ ] 15.4 Validar com a clínica piloto: guarda do prontuário e receita sem assinatura digital. _R15.6_

- [ ] 16. Verificação final
  - [ ] 16.1 `mvn test` e, com Postgres local, `RUN_LOCAL_POSTGRES_TESTS=true mvn verify`. _Todos_
  - [ ] 16.2 Roteiro manual no perfil `local-postgres`: recepção marca e registra a chegada; médico inicia, registra, prescreve e finaliza; conferir a agenda e os históricos. _Todos_
