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
| Recepção | Tela `/atendimento` | Vê o quadro do dia, registra chegada, falta e encaixe, chama o paciente e desfaz a chegada registrada por engano |
| Médico | Tela `/consultorio` | Vê a fila do próprio profissional, inicia a consulta, registra o prontuário, imprime a receita e finaliza |
| Gestor | Tela `/atendimento` | Consulta o histórico de eventos do atendimento (quem fez e quando), sem o texto clínico |

O perfil `MEDICO` abre a fila de atendimento e o consultório. A recepção abre a fila, a chegada e o desfazer.
O gestor consulta os eventos.

O agendamento percorre `MARCADO` ou `CONFIRMADO`, depois `AGUARDANDO` (chegada), `EM_CONSULTA` (consulta
iniciada) e `EXECUTADO` (alta). Esses três últimos continuam ocupando o horário do profissional. Confirmar,
cancelar ou remarcar fica disponível enquanto o agendamento está `MARCADO` ou `CONFIRMADO`. O bot deixa de
tratar como consulta em aberto, e de oferecer o horário, a partir da chegada. `FALTOU` encerra o
agendamento sem atendimento e libera o horário.

## Rotina da recepção

1. **Quadro do dia:** abra `/atendimento` e escolha a data. O quadro tem as colunas A chegar (`MARCADO` e
   `CONFIRMADO`, com as faltas separadas no fim), Aguardando, Chamado, Em consulta e Atendidos. Cada cartão
   traz paciente, horário, profissional e, quando houver, a chegada, o tempo de espera e a hora da chamada.
   A busca filtra por nome ou CPF (atalho `/`), e o filtro de profissional reduz o quadro. Anamnese,
   evolução, CID e receita ficam no consultório.
   O quadro e a fila do consultório se atualizam sozinhos (indicador "Ao vivo"): cada chegada, chamada,
   falta, encaixe, início e finalização avisa as telas da clínica, que recarregam a lista. O aviso leva só o
   tipo e os identificadores, sem nome nem texto clínico. Sem conexão ao vivo, as telas recarregam a cada 60
   segundos.
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
4. **Chamar:** com o paciente `AGUARDANDO`, a recepção ou o médico (só nos atendimentos do próprio
   profissional) registra a chamada. O cartão passa para a coluna Chamado com a hora. Chamar de novo
   atualiza a hora. A chamada não muda o status: a consulta começa quando o médico inicia.
5. **Falta:** no agendamento `MARCADO` ou `CONFIRMADO` cujo horário já começou, marque a falta. O
   agendamento fica `FALTOU` e o horário volta a ficar livre. Desfazer a falta devolve o status anterior
   (`CONFIRMADO` quando houve confirmação, `MARCADO` nos demais casos), desde que o horário não tenha sido
   ocupado por outra consulta. Nesse caso o sistema recusa e a recepção remarca pela agenda.
6. **Encaixe:** paciente sem agendamento. Informe profissional, especialidade, convênio (opcional), CPF,
   nascimento, nome e telefone. A busca por nome ou CPF preenche o paciente já cadastrado. O sistema cria o
   agendamento com origem `RECEPCAO`, de 15 minutos a partir de agora, já `AGUARDANDO`, e o atendimento
   `AGUARDANDO`. O encaixe não verifica sobreposição com a agenda do profissional: ele entra na fila de
   chegada como qualquer outro paciente.

Cada passo grava quem fez e quando, no atendimento e no agendamento. O histórico de eventos da recepção e do
gestor traz o tipo do evento, o usuário e o horário.

## Rotina do médico

Abra `/consultorio`. A fila lista os atendimentos do profissional vinculado ao seu usuário. Quem está
`AGUARDANDO` aparece pela ordem de chegada, com o tempo de espera. Usuário sem profissional vinculado vê o
aviso de que falta o vínculo, e a lista fica vazia. O médico lê e grava o prontuário desse profissional.
Na fila, "Chamar" registra a chamada do paciente `AGUARDANDO` (a recepção vê no quadro) e "Iniciar" abre a
consulta.

**Modelos de texto:** cada médico mantém os próprios modelos (botão "Modelos" no prontuário). Um modelo tem
atalho (2 a 30 letras minúsculas, números, `_` ou `-`), título opcional e texto de até 4000 caracteres. Num
campo da nota, digitar `.atalho` seguido de espaço ou Tab troca o atalho pelo texto. O diálogo também
insere o modelo no último campo usado. O atalho não se repete entre os modelos do mesmo médico.

**Favoritos de medicamento:** "Favoritar" num item da receita guarda medicamento, apresentação, posologia,
via, quantidade, uso contínuo e observação nos favoritos do médico. Favoritar de novo o mesmo medicamento,
apresentação e posologia atualiza o favorito. "★ Favoritos" abre a lista com filtro para incluir o item na
receita em rascunho.

Enquanto o atendimento está `EM_CONSULTA`, o registro nasce como rascunho e passa a valer ao liberar.
Rascunho se altera até a liberação. Registro liberado se corrige por inativação, com justificativa, e
continua no histórico. Fora de `EM_CONSULTA` o sistema recusa gravar anamnese, evolução, CID ou receita.

1. **Iniciar:** inicie a consulta de um atendimento `AGUARDANDO`. O atendimento passa a `EM_CONSULTA`, com o
   horário de início e o usuário, e o agendamento também passa a `EM_CONSULTA`. O médico inicia o atendimento
   do próprio profissional. Com outra consulta desse profissional ainda `EM_CONSULTA`, o sistema recusa até
   ela terminar.
2. **Nota da consulta (SOAP):** a tela organiza a anamnese em quatro blocos. Subjetivo: queixa principal,
   história da doença atual, medicamentos em uso, antecedentes e hábitos. Objetivo: exame físico.
   Avaliação: impressão clínica e CID. Plano: conduta, receitas e nota complementar. O rascunho grava
   sozinho enquanto o médico digita. Liberar exige a queixa principal. Cada atendimento tem no máximo uma
   anamnese ativa. Para corrigir uma liberada, inative com justificativa e crie outra. A inativada continua
   visível no histórico. A coluna de histórico mostra as consultas finalizadas anteriores na ordem de
   leitura APSO (avaliação e plano primeiro), com o botão para repetir uma receita.
   **Alergias** são do paciente, valem para todas as consultas e aparecem no cabeçalho do prontuário.
   Incluir ou inativar (com justificativa) exige consulta `EM_CONSULTA` do médico. A mesma alergia fica
   uma vez só entre as ativas. Alergia escrita em anamnese antiga aparece como "registrado em consulta
   anterior" até ser cadastrada no paciente.
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
7. **Finalizar e assinar:** a tela usa liberação única. Num só passo, e numa só transação, o sistema libera
   os rascunhos com conteúdo (anamnese, notas complementares e receitas), descarta os vazios por inativação
   com a justificativa "Rascunho vazio descartado na finalização" e encerra a consulta. Rascunho incompleto
   barra tudo e nada é gravado: anamnese sem queixa principal, nota complementar com peso, altura ou retorno
   mas sem texto, receita sem item com medicamento e posologia. Receita a liberar exige conselho e UF do
   profissional. Precisa sobrar ao menos uma anamnese ou uma nota complementar liberada. Informe o motivo
   da alta: `ALTA_MEDICA`, `ENCAMINHADO`, `DESISTENCIA` ou `OUTRO`. Desfecho (`ALTA`, `RETORNO` ou
   `ENCAMINHAMENTO`), orientações e data de retorno são opcionais. O atendimento fica `FINALIZADO` e o
   agendamento fica `EXECUTADO`. O horário continua ocupado. A liberação registro a registro e o
   `finalizar` antigo (que exige tudo já liberado) continuam na API para compatibilidade.

8. **Folha da consulta:** com o atendimento `FINALIZADO`, "Imprimir consulta" (no cabeçalho do prontuário e
   em cada consulta do histórico) abre a folha para impressão: clínica, paciente (CPF, nascimento e
   convênio), profissional, horários de chegada, início e fim, alergias, nota SOAP, CIDs ativos, itens das
   receitas liberadas, notas complementares, alta e campo de assinatura com nome, conselho e UF. Só entram
   registros liberados e ativos; rascunho e registro inativado ficam de fora. O rodapé traz quem finalizou,
   quando, e quem imprimiu. Cada impressão fica na auditoria do prontuário (`IMPRESSAO_CONSULTA`).

A leitura do prontuário e do histórico do paciente também fica na auditoria.

## Propriedades

| Propriedade | Padrão | Uso |
|---|---|---|
| `atendimento.enabled` | `false` | Liga o fluxo clínico (fila, chegada e consultório). Desligada, a agenda atual segue sem este fluxo |
| `atendimento.receita.validade-dias` | `30` | Dias de validade gravados ao liberar a receita |
| `mandalore.clientes.<id>.agenda` | já existe | `mandalore` habilita chegada e consultório na conta. Outro valor: as rotas dessa conta respondem 404 |
| `mandalore.clientes.<id>.nome` | `admin.nome` | Nome da clínica na impressão da receita. Sem esta propriedade, a impressão usa `admin.nome` |

Por clínica, no `clientes.properties`: `mandalore.clientes.<id>.agenda` e `mandalore.clientes.<id>.nome`.

O aviso ao vivo (`/atendimento/api/eventos` e `/consultorio/api/eventos`, SSE) fica na memória da
instância. Com mais de uma instância da aplicação, cada tela só recebe os avisos da instância em que está
conectada; as outras mudanças aparecem no recarregamento de 60 segundos. A resposta sai com
`X-Accel-Buffering: no` e `Cache-Control: no-cache, no-transform`, e o servidor manda um ping a cada 25
segundos. No Coolify (Traefik ou Caddy) não há configuração extra. Middleware de compressão ou de buffer
adicionado à mão no proxy não pode valer para `/atendimento/api/eventos` e `/consultorio/api/eventos`.

## Segurança

- Cada clínica enxerga os próprios atendimentos. A conta é a chave de separação no banco. Identificador de
  outra clínica responde como não encontrado.
- A recepção trabalha na fila, na chegada e no desfazer. Anamnese, evolução, CID e receita ficam com o
  médico do atendimento.
- Registro clínico permanece no banco. A correção é inativar (ou cancelar a receita) com justificativa.
- O texto clínico fica de fora dos logs, dos eventos de histórico, da fila da recepção e do OpenClaw. Os
  eventos de liberação, descarte e alergia levam só o tipo e o identificador do registro.
- Cada leitura do prontuário, do histórico do paciente e da impressão da receita grava auditoria: usuário,
  atendimento, ação, IP e horário.
- Nenhuma rotina apaga prontuário sozinha. A guarda é decisão da clínica, alinhada com o jurídico antes do
  uso em produção.
- "Finalizar e assinar" identifica o médico pelo login. Não é assinatura digital ICP-Brasil nem sistema
  certificado SBIS/CFM (nível NGS2). Pela Resolução CFM 1.821/2007 e pela Lei 13.787/2018, só sistema
  certificado com assinatura ICP-Brasil dispensa o papel. Até lá, a clínica mantém a via impressa e
  assinada do registro (folha da consulta e receita), guarda o prontuário por no mínimo 20 anos e trata o dado de saúde como sensível
  (LGPD, art. 11). A receita impressa com assinatura manual segue válida.

## Checklist para ligar numa clínica

1. A conta já usa a agenda do Mandalore: `mandalore.clientes.<id>.agenda=mandalore`.
2. Vincule um usuário de perfil `MEDICO` ao profissional da agenda, um usuário por profissional, da mesma
   conta. Sem o vínculo, o consultório avisa e a fila do médico fica vazia.
3. Preencha o número do conselho e a UF nesse profissional. Os dois entram na liberação da receita e na
   impressão.
4. Ligue `atendimento.enabled=true` depois do vínculo e do conselho. Com `false`, chegada e consultório
   permanecem desligados e a agenda atual segue sem este fluxo.
5. Treine a recepção na fila, na chegada e no desfazer, e o médico em iniciar, registrar, imprimir e
   finalizar. A receita e a folha da consulta desta versão seguem para impressão e assinatura manual, e a
   clínica guarda as vias assinadas.

## O que esta versão deixa para depois

Troca de médico no meio do atendimento, sinais vitais, atestado, pedido de exame, catálogo de medicamentos
e de CID, assinatura digital, PDF da receita, reabrir atendimento finalizado, painel de chamada na TV,
aviso ao vivo entre várias instâncias e faturamento. Clínica com agenda em outro sistema (por exemplo `tasy`) segue
com o fluxo clínico lá.
