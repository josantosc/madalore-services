# Requisitos: Atendimento clínico (chegada, consulta, anamnese/evolução, receita e alta)

## Contexto

Hoje o Mandalore tem uma agenda própria (schema `agenda` no PostgreSQL) para clínicas sem integração: o bot e a
recepção marcam, confirmam, cancelam e remarcam. O agendamento termina em `MARCADO`/`CONFIRMADO`. Não existe
chegada do paciente, consulta, prontuário, receita nem alta.

Esta feature acrescenta o ciclo clínico depois do agendamento, seguindo o fluxo observado no Tasy (Oracle):

```
agendamento ──► chegada (cria o atendimento) ──► consulta iniciada ──► anamnese/evolução/CID/receita ──► alta
 (agenda_consulta N/CN)   (A + atendimento_paciente)     (O)                                              (E)
```

O Postgres do Mandalore **não** replica as tabelas do Tasy. As tabelas clínicas novas usam os nomes e os campos do
Tasy como referência (`atendimento_paciente`, `evolucao_paciente`, `med_receita`...), mas ficam ligadas às tabelas
próprias do Mandalore (`agenda.agendamento`, `agenda.paciente`, `agenda.profissional`). Veja o mapeamento no `design.md`.

## Glossário

- **Atendimento**: a passagem do paciente pela clínica para uma consulta. Equivale a `ATENDIMENTO_PACIENTE` (tipo 8, ambulatorial).
- **Chegada**: a recepção registra que o paciente chegou. Cria o atendimento. Equivale ao status `A` do Tasy.
- **Registro clínico**: anamnese, evolução, diagnóstico (CID) e receita.
- **Liberar**: confirmar o registro. Depois de liberado, o texto não muda mais. Equivale a `DT_LIBERACAO` do Tasy.
- **Conta**: a clínica (conta do Chatwoot). É a chave de separação dos dados (`conta_id`).

## Fora do escopo desta versão

- Encaixe e chegada sem agendamento prévio.
- Troca de médico durante o atendimento (`ATENDIMENTO_TROCA_MEDICO`).
- Sinais vitais, atestado, pedido de exame, catálogo de medicamentos e de CID.
- Assinatura digital ICP-Brasil e PDF da receita (a v1 gera visão para impressão).
- Reabrir atendimento finalizado, falta/não comparecimento, faturamento/TISS.
- Clínicas com `agenda=tasy`: o fluxo clínico delas continua no Tasy.

## Requisitos

### R1. Registrar a chegada do paciente

**Como** recepcionista, **quero** registrar a chegada de um paciente agendado **para** colocá-lo na fila do médico.

1. QUANDO a recepção registrar a chegada de um agendamento `MARCADO` ou `CONFIRMADO` do dia atual, O SISTEMA DEVE criar um atendimento com status `AGUARDANDO`, `dt_entrada` igual ao horário atual da clínica e o usuário que registrou.
2. QUANDO a chegada for registrada, O SISTEMA DEVE mudar o status do agendamento para `AGUARDANDO` na mesma transação.
3. QUANDO a chegada for registrada, O SISTEMA DEVE copiar para o atendimento o paciente, o profissional, a especialidade e o convênio do agendamento.
4. SE o agendamento não for do dia atual (fuso `America/Sao_Paulo`), ENTÃO O SISTEMA DEVE recusar com o código `CHEGADA_FORA_DO_DIA`.
5. SE o agendamento estiver `CANCELADO`, `REMARCADO`, `AGUARDANDO`, `EM_CONSULTA` ou `EXECUTADO`, ENTÃO O SISTEMA DEVE recusar com o código `STATUS_INVALIDO_PARA_CHEGADA` e não criar atendimento.
6. SE a chegada for enviada duas vezes (duplo clique, requisição repetida), ENTÃO O SISTEMA DEVE manter um único atendimento ativo por agendamento e devolver o mesmo `atendimentoId`.
7. QUANDO a chegada for registrada, O SISTEMA DEVE gravar o evento `CHEGADA` no histórico do atendimento e o evento `CHEGOU` em `agenda.agendamento_evento`, com usuário e horário.

### R2. Fila do dia

**Como** recepcionista ou médico, **quero** ver os pacientes do dia com o status de cada um **para** saber quem está esperando.

1. QUANDO a recepção abrir a fila de uma data, O SISTEMA DEVE listar os agendamentos ativos dessa data da clínica (`MARCADO`, `CONFIRMADO`, `AGUARDANDO`, `EM_CONSULTA`, `EXECUTADO`) ordenados por horário.
2. A fila da recepção DEVE mostrar paciente, horário, profissional, status e, se houver, hora da chegada. A fila da recepção NÃO DEVE mostrar nenhum conteúdo clínico.
3. QUANDO o médico abrir a própria fila, O SISTEMA DEVE listar só os atendimentos do profissional vinculado ao usuário dele, ordenados pela chegada para quem está `AGUARDANDO`.
4. A fila DEVE mostrar o tempo de espera de quem está `AGUARDANDO`.

### R3. Desfazer a chegada

**Como** recepcionista, **quero** desfazer uma chegada registrada por engano **para** corrigir a fila.

1. QUANDO a recepção desfizer a chegada de um atendimento `AGUARDANDO`, O SISTEMA DEVE marcar o atendimento como `CANCELADO` (com motivo obrigatório e usuário) e devolver o agendamento ao status anterior à chegada (`CONFIRMADO` se houve confirmação, senão `MARCADO`).
2. SE o atendimento já estiver `EM_CONSULTA` ou `FINALIZADO`, ENTÃO O SISTEMA DEVE recusar com o código `ATENDIMENTO_EM_ANDAMENTO`.
3. DEPOIS de desfeita a chegada, O SISTEMA DEVE permitir registrar uma nova chegada para o mesmo agendamento.

### R4. Iniciar a consulta médica

**Como** médico, **quero** iniciar a consulta de um paciente que chegou **para** começar o registro clínico.

1. QUANDO o médico iniciar a consulta de um atendimento `AGUARDANDO`, O SISTEMA DEVE mudar o atendimento para `EM_CONSULTA`, gravar `dt_atend_medico` e `nm_usuario_atend`, e mudar o agendamento para `EM_CONSULTA`, tudo na mesma transação.
2. SE o usuário não estiver vinculado ao profissional do atendimento, ENTÃO O SISTEMA DEVE recusar com HTTP 403.
3. SE o atendimento não estiver `AGUARDANDO`, ENTÃO O SISTEMA DEVE recusar com o código `STATUS_INVALIDO_PARA_CONSULTA`.
4. SE o profissional já tiver outro atendimento `EM_CONSULTA`, ENTÃO O SISTEMA DEVE recusar com o código `PROFISSIONAL_EM_CONSULTA`.
5. QUANDO a consulta for iniciada, O SISTEMA DEVE gravar o evento `CONSULTA_INICIADA` no atendimento e `CONSULTA_INICIADA` em `agenda.agendamento_evento`.

### R5. Registrar a anamnese

**Como** médico, **quero** registrar a anamnese do paciente **para** documentar a história clínica da consulta.

1. ENQUANTO o atendimento estiver `EM_CONSULTA`, O SISTEMA DEVE permitir ao médico dono do atendimento criar e editar a anamnese como rascunho, com os campos: queixa principal, história da doença atual, antecedentes pessoais, antecedentes familiares, medicamentos em uso, alergias, hábitos e exame físico.
2. O SISTEMA DEVE exigir ao menos a queixa principal para liberar a anamnese.
3. QUANDO o médico liberar a anamnese, O SISTEMA DEVE gravar `dt_liberacao` e impedir qualquer alteração dos campos.
4. SE o médico precisar corrigir uma anamnese liberada, ENTÃO O SISTEMA DEVE permitir inativá-la com justificativa obrigatória e criar uma nova. A anamnese inativada DEVE continuar visível no histórico.
5. O SISTEMA DEVE permitir no máximo uma anamnese ativa por atendimento.
6. SE o atendimento não estiver `EM_CONSULTA`, ENTÃO O SISTEMA DEVE recusar a gravação com o código `ATENDIMENTO_NAO_EM_CONSULTA`.

### R6. Registrar a evolução

**Como** médico, **quero** registrar a evolução clínica **para** documentar achados, conduta e plano.

1. ENQUANTO o atendimento estiver `EM_CONSULTA`, O SISTEMA DEVE permitir ao médico dono criar e editar evoluções em rascunho, com texto livre, peso e altura opcionais e data de retorno prevista opcional.
2. O SISTEMA DEVE permitir várias evoluções por atendimento.
3. QUANDO o médico liberar a evolução, O SISTEMA DEVE gravar `dt_liberacao` e impedir alteração do texto.
4. SE o texto da evolução estiver vazio, ENTÃO O SISTEMA DEVE recusar a liberação.
5. QUANDO o médico inativar uma evolução liberada, O SISTEMA DEVE exigir justificativa, gravar `dt_inativacao` e usuário, e manter o registro no histórico.
6. SE o atendimento não estiver `EM_CONSULTA`, ENTÃO O SISTEMA DEVE recusar a gravação com o código `ATENDIMENTO_NAO_EM_CONSULTA`.

### R7. Registrar o diagnóstico (CID)

**Como** médico, **quero** informar o diagnóstico do atendimento **para** que fique no prontuário.

1. ENQUANTO o atendimento estiver `EM_CONSULTA`, O SISTEMA DEVE permitir informar diagnósticos com código CID, descrição e tipo (`PRINCIPAL` ou `SECUNDARIO`).
2. O SISTEMA DEVE validar o formato do código CID (letra seguida de dois dígitos, com subcategoria opcional, por exemplo `J06` ou `J06.9`).
3. O SISTEMA DEVE permitir no máximo um diagnóstico `PRINCIPAL` ativo por atendimento.
4. QUANDO o médico remover um diagnóstico, O SISTEMA DEVE inativá-lo com justificativa e não apagá-lo.

### R8. Gerar a receita

**Como** médico, **quero** emitir uma receita para o paciente **para** prescrever o tratamento.

1. ENQUANTO o atendimento estiver `EM_CONSULTA`, O SISTEMA DEVE permitir criar e editar receita em rascunho com um ou mais itens (medicamento, apresentação, posologia, via, quantidade, uso contínuo e observação) e observação geral.
2. O SISTEMA DEVE exigir ao menos um item com medicamento e posologia para liberar a receita.
3. QUANDO o médico liberar a receita, O SISTEMA DEVE gravar `dt_liberacao`, calcular a validade (padrão 30 dias, configurável por clínica) e impedir alteração dos itens.
4. QUANDO o médico cancelar uma receita liberada, O SISTEMA DEVE exigir justificativa e manter o registro.
5. QUANDO a receita estiver liberada, O SISTEMA DEVE oferecer uma visão para impressão com: dados da clínica, paciente, data, itens, nome do médico, número do conselho e UF.
6. SE o profissional não tiver número do conselho e UF cadastrados, ENTÃO O SISTEMA DEVE recusar a liberação com o código `PROFISSIONAL_SEM_CONSELHO`.
7. QUANDO a visão para impressão for aberta, O SISTEMA DEVE registrar o acesso na auditoria do prontuário.

### R9. Finalizar o atendimento

**Como** médico, **quero** finalizar o atendimento **para** encerrar a consulta e baixar o agendamento como executado.

1. QUANDO o médico finalizar um atendimento `EM_CONSULTA`, O SISTEMA DEVE gravar `dt_fim_consulta`, `dt_alta`, `nm_usuario_alta`, o motivo da alta e o desfecho, mudar o atendimento para `FINALIZADO` e o agendamento para `EXECUTADO`, na mesma transação.
2. O SISTEMA DEVE exigir ao menos um registro clínico liberado (anamnese ou evolução) para finalizar, senão recusar com o código `SEM_REGISTRO_CLINICO`.
3. SE houver anamnese, evolução ou receita em rascunho, ENTÃO O SISTEMA DEVE recusar com o código `RASCUNHO_PENDENTE` e informar quais são.
4. O SISTEMA DEVE exigir o motivo da alta. O desfecho (`ALTA`, `RETORNO`, `ENCAMINHAMENTO`), as orientações e a data de retorno são opcionais.
5. QUANDO o atendimento for finalizado, O SISTEMA DEVE manter o horário ocupado na agenda (o agendamento `EXECUTADO` continua impedindo nova marcação no mesmo horário).
6. DEPOIS de finalizado, O SISTEMA DEVE impedir qualquer alteração dos registros clínicos desse atendimento.
7. QUANDO o atendimento for finalizado, O SISTEMA DEVE gravar o evento `FINALIZADO` no atendimento e `EXECUTADO` em `agenda.agendamento_evento`.

### R10. Perfis e permissões

1. O SISTEMA DEVE criar o perfil `MEDICO` com acesso ao módulo de consultório e à fila de atendimento.
2. O SISTEMA DEVE manter o acesso da recepção limitado à fila, à chegada e a desfazer a chegada. A recepção NÃO DEVE ler nem gravar anamnese, evolução, diagnóstico ou receita.
3. O SISTEMA DEVE permitir vincular um usuário a um profissional da agenda (um usuário para um profissional) e cadastrar o número do conselho e a UF do profissional.
4. SE um usuário sem vínculo com profissional abrir o consultório, ENTÃO O SISTEMA DEVE mostrar a mensagem de que falta o vínculo e não listar nenhum atendimento.
5. O médico SÓ DEVE ler e gravar registros clínicos dos atendimentos do profissional vinculado a ele. Os módulos do consultório podem ser concedidos a outros perfis só por ajuste explícito de telas do usuário.

### R11. Isolamento por clínica

1. Toda tabela nova DEVE ter `conta_id` e toda consulta DEVE filtrar por ele.
2. SE o usuário não puder ver a conta informada, ENTÃO O SISTEMA DEVE recusar com HTTP 403, como nas demais telas da central.
3. SE um identificador (atendimento, evolução, receita) pertencer a outra conta, ENTÃO O SISTEMA DEVE responder como "não encontrado", sem revelar que existe.

### R12. Rastreabilidade e auditoria

1. O SISTEMA DEVE registrar cada transição de status do atendimento em `atendimento_evento`, com tipo, usuário e horário.
2. O SISTEMA DEVE registrar em uma tabela de auditoria cada leitura do prontuário de um paciente (usuário, atendimento, ação, IP e horário).
3. O SISTEMA NÃO DEVE apagar fisicamente registros clínicos. A correção é por inativação com justificativa.
4. O SISTEMA DEVE permitir ao gestor consultar o histórico de eventos de um atendimento sem ver o conteúdo clínico.

### R13. Integridade da agenda

1. O SISTEMA DEVE ampliar os status do agendamento com `AGUARDANDO`, `EM_CONSULTA` e `EXECUTADO`.
2. Os status `MARCADO`, `CONFIRMADO`, `AGUARDANDO`, `EM_CONSULTA` e `EXECUTADO` DEVEM ocupar o horário do profissional. O banco DEVE impedir dois deles no mesmo horário.
3. Um agendamento `AGUARDANDO`, `EM_CONSULTA` ou `EXECUTADO` NÃO DEVE ser confirmado, cancelado nem remarcado pela agenda. Se tentarem, O SISTEMA DEVE recusar com o código `AGENDAMENTO_EM_ATENDIMENTO`.
4. O bot NÃO DEVE listar como "consulta em aberto" um agendamento `AGUARDANDO`, `EM_CONSULTA` ou `EXECUTADO`, e NÃO DEVE oferecer o horário dele como livre.
5. A conferência diária e as pendências existentes DEVEM continuar funcionando sem alteração de comportamento para `MARCADO`, `CONFIRMADO`, `CANCELADO` e `REMARCADO`.

### R14. Habilitação por clínica

1. O SISTEMA DEVE ligar o fluxo clínico só em clínicas com `mandalore.clientes.<id>.agenda=mandalore`.
2. SE a clínica usar outro backend de agenda, ENTÃO as rotas do consultório e da chegada DEVEM responder HTTP 404 para a conta dela.
3. O fluxo DEVE poder ser desligado por propriedade global (`atendimento.enabled=false`), sem afetar a agenda atual.

### R15. Requisitos não funcionais

1. **Concorrência:** duas ações simultâneas sobre o mesmo agendamento ou atendimento DEVEM resultar em uma só transição válida, sem estado inconsistente.
2. **Fuso:** horários locais da clínica (`America/Sao_Paulo`), como o restante do schema `agenda`. Datas de criação em `TIMESTAMPTZ`.
3. **Compatibilidade:** o schema DEVE ser criado de forma idempotente na subida, pelo mesmo mecanismo dos demais (`db/*/schema.sql`), sem exigir ferramenta de migração nova.
4. **Dados sensíveis:** o conteúdo clínico NÃO DEVE aparecer em logs, em eventos de histórico, no OpenClaw nem em respostas da recepção.
5. **Desempenho:** a fila do dia DEVE responder em menos de 500 ms para 300 atendimentos por clínica.
6. **Retenção:** nenhuma rotina de exclusão automática de registros clínicos. A política de guarda do prontuário é decisão da clínica (validar com o jurídico antes de produção).
