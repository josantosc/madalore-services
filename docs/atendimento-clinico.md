# Atendimento clínico do Mandalore

Para clínicas que usam a agenda própria do Mandalore. Depois da marcação, a recepção registra a chegada e o
médico conduz a consulta no consultório: anamnese, evolução, diagnóstico (CID), receita e alta. O prontuário
fica no PostgreSQL do Mandalore, ligado ao agendamento, ao paciente e ao profissional da agenda.

O fluxo vale na conta com `mandalore.clientes.<id>.agenda=mandalore` e com `atendimento.enabled` ligado.
Desligado, a agenda segue como está: o bot e a recepção marcam, confirmam, cancelam e remarcam. O schema
`atendimento` é criado na subida, no mesmo PostgreSQL da agenda.

Nesta versão o Mandalore é o prontuário da clínica. Chegada e consulta não geram pendência para lançar em
outro sistema. A conferência e as pendências da agenda seguem iguais para marcação, confirmação, cancelamento
e remarcação.

## Como funciona

| Quem faz | Onde | O que acontece |
|---|---|---|
| Recepção | Tela `/atendimento` | Vê a fila do dia, registra a chegada e desfaz a chegada registrada por engano |
| Médico | Tela `/consultorio` | Vê a fila do próprio profissional, inicia a consulta, registra o prontuário, imprime a receita e finaliza |
| Gestor | Tela `/atendimento` | Consulta o histórico de eventos do atendimento (quem fez e quando), sem o texto clínico |

O perfil `MEDICO` abre a fila de atendimento e o consultório. A recepção abre a fila, a chegada e o desfazer.
O gestor consulta os eventos.

O agendamento percorre `MARCADO` ou `CONFIRMADO`, depois `AGUARDANDO` (chegada), `EM_CONSULTA` (consulta
iniciada) e `EXECUTADO` (alta). Esses três últimos continuam ocupando o horário do profissional. Confirmar,
cancelar ou remarcar fica disponível enquanto o agendamento está `MARCADO` ou `CONFIRMADO`. O bot deixa de
tratar como consulta em aberto, e de oferecer o horário, a partir da chegada.

## Rotina da recepção

1. **Fila do dia:** abra `/atendimento` e escolha a data. A lista traz os agendamentos da clínica naquele dia
   (`MARCADO`, `CONFIRMADO`, `AGUARDANDO`, `EM_CONSULTA` e `EXECUTADO`), em ordem de horário: paciente,
   horário, profissional, status e, quando houver, a hora da chegada. Quem está `AGUARDANDO` mostra o tempo
   de espera. Anamnese, evolução, CID e receita ficam no consultório.
2. **Chegada:** no agendamento `MARCADO` ou `CONFIRMADO` de hoje, registre a chegada. O sistema cria o
   atendimento `AGUARDANDO`, copia paciente, profissional, especialidade e convênio do agendamento e muda o
   agendamento para `AGUARDANDO`, com o usuário e o horário da clínica (fuso `America/Sao_Paulo`). O dia é o
   dia atual: agendamento de outra data é recusado, assim como cancelado, remarcado ou que já está em
   atendimento. Um segundo clique, ou duas recepções ao mesmo tempo no mesmo agendamento, devolve o
   atendimento já criado.
3. **Desfazer a chegada:** com o atendimento ainda `AGUARDANDO`, desfaça informando o motivo. O atendimento
   fica `CANCELADO` e o agendamento volta ao status de antes da chegada: `CONFIRMADO` quando houve
   confirmação, `MARCADO` nos demais casos. Em seguida a recepção pode registrar a chegada de novo. Consulta
   já iniciada ou atendimento já finalizado permanece: o sistema recusa o desfazer.

Cada passo grava quem fez e quando, no atendimento e no agendamento. O histórico de eventos da recepção e do
gestor traz o tipo do evento, o usuário e o horário.

## Rotina do médico

Abra `/consultorio`. A fila lista os atendimentos do profissional vinculado ao seu usuário. Quem está
`AGUARDANDO` aparece pela ordem de chegada, com o tempo de espera. Usuário sem profissional vinculado vê o
aviso de que falta o vínculo, e a lista fica vazia. O médico lê e grava o prontuário desse profissional.

Enquanto o atendimento está `EM_CONSULTA`, o registro nasce como rascunho e passa a valer ao liberar.
Rascunho se altera até a liberação. Registro liberado se corrige por inativação, com justificativa, e
continua no histórico. Fora de `EM_CONSULTA` o sistema recusa gravar anamnese, evolução, CID ou receita.

1. **Iniciar:** inicie a consulta de um atendimento `AGUARDANDO`. O atendimento passa a `EM_CONSULTA`, com o
   horário de início e o usuário, e o agendamento também passa a `EM_CONSULTA`. O médico inicia o atendimento
   do próprio profissional. Com outra consulta desse profissional ainda `EM_CONSULTA`, o sistema recusa até
   ela terminar.
2. **Anamnese:** grave o rascunho com queixa principal, história da doença atual, antecedentes pessoais,
   antecedentes familiares, medicamentos em uso, alergias, hábitos e exame físico. Liberar exige a queixa
   principal. Cada atendimento tem no máximo uma anamnese ativa. Para corrigir uma liberada, inative com
   justificativa e crie outra. A inativada continua visível no histórico.
3. **Evolução:** grave o rascunho em texto livre. Peso, altura e data de retorno prevista são opcionais.
   Pode haver várias evoluções no mesmo atendimento. Liberar exige texto. Para corrigir uma liberada, inative
   com justificativa.
4. **CID:** informe o código, a descrição e o tipo (`PRINCIPAL` ou `SECUNDARIO`). O código é uma letra e
   dois dígitos, com subcategoria opcional (`J06`, `J06.9`). Há no máximo um diagnóstico principal ativo, e
   o mesmo CID fica uma vez só entre os ativos do atendimento. Remover inativa o diagnóstico com
   justificativa.
5. **Receita:** grave o rascunho com um ou mais itens (medicamento, apresentação, posologia, via, quantidade,
   uso contínuo e observação), o tipo (comum ou especial) e uma observação geral. Liberar exige ao menos um
   item com medicamento e posologia, e o profissional com número do conselho e UF. Na liberação o sistema
   grava a validade (`atendimento.receita.validade-dias`, padrão 30 dias). Cancelar uma receita liberada
   exige justificativa.
6. **Impressão:** abra a visão da receita liberada. Ela traz os dados da clínica, o paciente, a data, os
   itens, o nome do médico, o número do conselho e a UF. O nome da clínica sai de
   `mandalore.clientes.<id>.nome` e, na ausência dele, de `admin.nome`. Abrir a impressão fica na auditoria
   do prontuário. A visão é para impressão e assinatura manual do médico.
7. **Finalizar:** encerre a consulta `EM_CONSULTA` com ao menos uma anamnese ou uma evolução já liberada, e
   com a anamnese, as evoluções e as receitas todas liberadas ou inativadas (rascunho aberto impede a alta e
   o sistema informa quais são). Informe o motivo da alta: `ALTA_MEDICA`, `ENCAMINHADO`, `DESISTENCIA` ou
   `OUTRO`. Desfecho (`ALTA`, `RETORNO` ou `ENCAMINHAMENTO`), orientações e data de retorno são opcionais.
   O atendimento fica `FINALIZADO` e o agendamento fica `EXECUTADO`. O horário continua ocupado. Os
   registros clínicos desse atendimento permanecem como foram liberados.

A leitura do prontuário e do histórico do paciente também fica na auditoria.

## Propriedades

| Propriedade | Padrão | Uso |
|---|---|---|
| `atendimento.enabled` | `false` | Liga o fluxo clínico (fila, chegada e consultório). Desligada, a agenda atual segue sem este fluxo |
| `atendimento.receita.validade-dias` | `30` | Dias de validade gravados ao liberar a receita |
| `mandalore.clientes.<id>.agenda` | já existe | `mandalore` habilita chegada e consultório na conta. Outro valor: as rotas dessa conta respondem 404 |
| `mandalore.clientes.<id>.nome` | `admin.nome` | Nome da clínica na impressão da receita. Sem esta propriedade, a impressão usa `admin.nome` |

Por clínica, no `clientes.properties`: `mandalore.clientes.<id>.agenda` e `mandalore.clientes.<id>.nome`.

## Segurança

- Cada clínica enxerga os próprios atendimentos. A conta é a chave de separação no banco. Identificador de
  outra clínica responde como não encontrado.
- A recepção trabalha na fila, na chegada e no desfazer. Anamnese, evolução, CID e receita ficam com o
  médico do atendimento.
- Registro clínico permanece no banco. A correção é inativar (ou cancelar a receita) com justificativa.
- O texto clínico fica de fora dos logs, dos eventos de histórico, da fila da recepção e do OpenClaw.
- Cada leitura do prontuário, do histórico do paciente e da impressão da receita grava auditoria: usuário,
  atendimento, ação, IP e horário.
- Nenhuma rotina apaga prontuário sozinha. A guarda é decisão da clínica, alinhada com o jurídico antes do
  uso em produção.

## Checklist para ligar numa clínica

1. A conta já usa a agenda do Mandalore: `mandalore.clientes.<id>.agenda=mandalore`.
2. Vincule um usuário de perfil `MEDICO` ao profissional da agenda, um usuário por profissional, da mesma
   conta. Sem o vínculo, o consultório avisa e a fila do médico fica vazia.
3. Preencha o número do conselho e a UF nesse profissional. Os dois entram na liberação da receita e na
   impressão.
4. Ligue `atendimento.enabled=true` depois do vínculo e do conselho. Com `false`, chegada e consultório
   permanecem desligados e a agenda atual segue sem este fluxo.
5. Treine a recepção na fila, na chegada e no desfazer, e o médico em iniciar, registrar, imprimir e
   finalizar. A receita desta versão segue para impressão e assinatura manual.

## O que esta versão deixa para depois

Encaixe e chegada sem agendamento, troca de médico no meio do atendimento, sinais vitais, atestado, pedido
de exame, catálogo de medicamentos e de CID, assinatura digital, PDF da receita, reabrir atendimento
finalizado, falta do paciente e faturamento. Clínica com agenda em outro sistema (por exemplo `tasy`) segue
com o fluxo clínico lá.
