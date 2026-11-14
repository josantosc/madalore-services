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

1. **Fila do dia:** abra `/atendimento` e escolha a data. A tela abre em **Lista**, uma linha por paciente,
   em grupos por prioridade: Aguardando (maior espera primeiro), Chamados, Atrasados para chegar, Em
   consulta, Próximos e, recolhido no fim, Atendidos e faltas. Cada linha traz horário, paciente,
   profissional, situação (chegada, tempo de espera, chamada com o local) e a ação principal (Chegou,
   Chamar ou Chamar de novo); as demais (Faltou, Desfazer chegada, Desfazer falta) ficam no menu "⋯". Espera
   a partir de 15 minutos marca a linha em âmbar, a partir de 30 em vermelho, e o chamado em violeta. Os
   botões no topo (Todos, A chegar, Aguardando, Chamado, Em consulta, Atendidos, Faltas) mostram a contagem e
   filtram a lista. A busca filtra por nome ou CPF (atalho `/`); com um paciente só no resultado, Enter
   executa a ação principal dele. O botão **Quadro** mostra as mesmas informações em colunas (A chegar,
   Aguardando, Chamado, Em consulta e Atendidos), melhor para poucos pacientes ou monitor grande. A tela
   lembra no navegador a última visualização escolhida. Anamnese, evolução, CID e receita ficam no
   consultório.
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
   **Check-in pelo WhatsApp:** o próprio paciente registra a chegada. A recepção mostra um QR code (no canto
   do painel da TV e em `/atendimento/checkin`, pelo botão "QR do check-in", para deixar num tablet ou
   imprimir). O QR abre o WhatsApp da clínica com a mensagem pronta `Cheguei ABC123`. O código muda todo
   dia, então quem não está na clínica não faz o check-in. O assistente pede CPF e data de nascimento na
   mesma mensagem e procura a consulta `MARCADO` ou `CONFIRMADO` de hoje. O check-in abre 60 minutos antes
   do horário; atrasado, vale até o fim do dia. A chegada segue a mesma regra do passo anterior, com o
   usuário `whatsapp`, e a fila mostra a etiqueta "Check-in WhatsApp". Três tentativas erradas, código de
   outro dia ou paciente sem consulta hoje recebem a orientação de procurar a recepção. Mensagem sem
   números no meio do check-in volta para o assistente normal. O paciente não é chamado pelo WhatsApp:
   a chamada segue pelo painel da TV. Precisa de `mandalore.clientes.<id>.whatsapp` no
   `clientes.properties`; sem ele, o QR não aparece.
3. **Desfazer a chegada:** com o atendimento ainda `AGUARDANDO`, desfaça informando o motivo. O atendimento
   fica `CANCELADO` e o agendamento volta ao status de antes da chegada: `CONFIRMADO` quando houve
   confirmação, `MARCADO` nos demais casos. Em seguida a recepção pode registrar a chegada de novo. Consulta
   já iniciada ou atendimento já finalizado permanece: o sistema recusa o desfazer.
4. **Chamar:** com o paciente `AGUARDANDO`, a recepção ou o médico (só nos atendimentos do próprio
   profissional) registra a chamada. O paciente passa para Chamados (coluna Chamado no Quadro) com a hora. Chamar de novo
   atualiza a hora. A chamada não muda o status: a consulta começa quando o médico inicia.
   Ao chamar, escolha o local para onde o paciente vai (`Consultório 3`, `Recepção`, `Financeiro`...). As
   opções vêm de `mandalore.clientes.<id>.painel.locais`; também dá para digitar outro local, com até 60
   caracteres, ou chamar sem local. A tela lembra o último local usado no navegador: no consultório,
   o botão "Chamar · Consultório 3" chama direto e "▾" troca o local. A recepção também chama o paciente
   já atendido no dia, por exemplo para o financeiro; o médico só chama quem está `AGUARDANDO`.
   **Painel da TV:** a sala de espera abre `/atendimento/painel?conta=<id>&chave=<chave>` em tela cheia.
   Não exige login: vale a chave da clínica em `mandalore.clientes.<id>.painel.chave` (mínimo de 16
   caracteres), no `clientes.properties`. Não use variável de ambiente `MANDALORE_CLIENTES_*`: o Spring a
   lê como cadastro de conta. Sem a propriedade, ou com chave errada, a resposta é 404. O painel mostra a chamada atual em
   destaque, com aviso sonoro e leitura em voz, e as cinco anteriores do dia. O paciente aparece com o
   primeiro nome e as iniciais (`Maria S. O.`), mais "Dirija-se a: <local>" (também lido em voz), o nome
   do profissional e a hora. Chamada sem local mostra só o profissional. CPF, especialidade e
   nome completo não saem do servidor. No primeiro acesso, um toque na tela ativa o som e a tela cheia
   (exigência do navegador). Para trocar a chave, mude a propriedade e atualize o link da TV.
5. **Falta:** no agendamento `MARCADO` ou `CONFIRMADO` cujo horário já começou, marque a falta. O
   agendamento fica `FALTOU` e o horário volta a ficar livre. Desfazer a falta devolve o status anterior
   (`CONFIRMADO` quando houve confirmação, `MARCADO` nos demais casos), desde que o horário não tenha sido
   ocupado por outra consulta. Nesse caso o sistema recusa e a recepção remarca pela agenda.
6. **Encaixe:** paciente sem agendamento. Informe profissional, especialidade, convênio (opcional), CPF,
   nascimento, nome e telefone. A busca por nome ou CPF preenche o paciente já cadastrado. O sistema cria o
   agendamento com origem `RECEPCAO`, de 15 minutos a partir de agora, já `AGUARDANDO`, e o atendimento
   `AGUARDANDO`. O encaixe não verifica sobreposição com a agenda do profissional: ele entra na fila de
   chegada como qualquer outro paciente.

7. **Declaração de comparecimento:** no menu "⋯" do paciente que já chegou (aguardando, em consulta ou
   atendido), "Declaração de comparecimento" abre o texto pronto com paciente, CPF, data, hora da chegada e
   profissional. `{saida}` é trocado na emissão pelo fim da consulta, ou pela hora da emissão se a consulta
   não terminou. "Emitir e imprimir" grava a declaração no atendimento e abre a folha com linha para
   assinatura e carimbo da clínica. A recepção não emite nem imprime atestado.

Cada passo grava quem fez e quando, no atendimento e no agendamento. O histórico de eventos da recepção e do
gestor traz o tipo do evento, o usuário e o horário.

**Consultar paciente:** o botão "Consultar paciente" abre `/atendimento/paciente`. Informe o CPF (com ou sem
pontuação) e o sistema mostra todos os agendamentos do paciente na clínica, do mais recente ao mais antigo:
data, profissional, especialidade, convênio, situação (marcado, confirmado, cancelado, faltou, atendido),
número do atendimento, local da última chamada e o histórico de cada um (marcação, confirmação, remarcação,
chegada, chamada, início e fim da consulta, falta), com usuário e horário. A recepção e o gestor não veem
conteúdo clínico: nem nota, nem CID, nem receita, nem os eventos clínicos. Um CPF com mais de um cadastro
(nascimentos diferentes) lista os cadastros para escolher. A ficha abre com um resumo (iniciais, idade,
CPF, telefone, convênio do último agendamento e último atendimento), os indicadores de consultas realizadas,
próximas, faltas e cancelados (um clique filtra a aba Agendamentos), os próximos agendamentos e as abas Linha
do tempo e Agendamentos, com busca no histórico. O médico vê também alergias e CIDs anteriores no resumo, o
indicador de receitas e as abas Consultas e Receitas. O CPF vai no corpo da requisição, nunca na URL.
Cada abertura fica na auditoria (`FICHA_PACIENTE`).

## Rotina do médico

Abra `/consultorio`. A fila lista os atendimentos do profissional vinculado ao seu usuário. Quem está
`AGUARDANDO` aparece pela ordem de chegada, com o tempo de espera. Usuário sem profissional vinculado vê o
aviso de que falta o vínculo, e a lista fica vazia. O médico lê e grava o prontuário desse profissional.
Na fila, "Chamar" registra a chamada do paciente `AGUARDANDO` (a recepção vê no quadro) e "Iniciar" abre a
consulta.

**Modelos de texto:** cada médico mantém os próprios modelos (botão "Modelos" no prontuário). Um modelo tem
atalho (2 a 30 letras minúsculas, números, `_` ou `-`), título opcional e texto de até 4000 caracteres.
Digitar `.atalho` seguido de espaço ou Tab troca o atalho pelo texto nos campos da nota, na observação da
receita, nas orientações da alta e no texto do atestado ou da declaração. O diálogo também insere o modelo
no último campo usado. O atalho não se repete entre os modelos do mesmo médico.
O texto do modelo aceita marcadores, trocados pelos dados do paciente quando o modelo entra no campo:
`{paciente}`, `{idade}`, `{nascimento}`, `{convenio}`, `{data}` (dia da consulta), `{hoje}`, `{hora}`,
`{chegada}`, `{saida}`, `{alergias}`, `{cid}` (CID principal), `{retorno}`, `{medico}`, `{especialidade}`,
`{crm}` e `{dias}`. No diálogo de modelos, um clique no marcador o inclui no texto. Marcador sem valor fica
como está, para o médico ver o que faltou. `{crm}` e `{dias}` só são trocados no atestado e na
declaração, na emissão.

**Atestado e declaração de comparecimento:** em "Conduta e prescrição", com a consulta em andamento ou
finalizada, "+ Atestado" e "+ Declaração de comparecimento" abrem o texto pronto, já com os dados do
paciente, para o médico revisar. No atestado, informe os dias de afastamento (vazio ou 0: só o período da
consulta) e, se o paciente autorizar, um dos CIDs do atendimento: o CID só sai no atestado com a caixa de
autorização marcada (Resolução CFM 1.658/2002). "Emitir e imprimir" grava o documento no atendimento e
abre a folha: clínica, paciente e CPF, texto, CID (quando houver), data, assinatura com nome, conselho e UF,
e o número do documento. Documento emitido não se edita: emita outro. A lista do bloco reimprime os
documentos do atendimento. Cada impressão fica na auditoria (`IMPRESSAO_DOCUMENTO`) e cada emissão gera o
evento `ATESTADO_EMITIDO` ou `DECLARACAO_EMITIDA` no histórico.

**Favoritos de medicamento:** "Favoritar" num item da receita guarda medicamento, apresentação, posologia,
via, quantidade, uso contínuo e observação nos favoritos do médico. Favoritar de novo o mesmo medicamento,
apresentação e posologia atualiza o favorito. "★ Favoritos" abre a lista com filtro para incluir o item na
receita em rascunho.

**Busca de medicamento na receita:** a partir de duas letras no campo "Medicamento", aparecem até oito
sugestões. A busca ignora acentos e aceita partes de palavras ("dipi gotas"). Os favoritos do médico vêm
primeiro, marcados com ★, e depois os medicamentos de rotina da clínica. Escolher uma sugestão (clique, ou
setas e Enter) preenche medicamento, apresentação, posologia, via e uso contínuo. Os favoritos preenchem
também quantidade e observação. A posologia sugerida fica destacada em amarelo até o médico editá-la: é uma
dose usual de adulto, e o médico confere antes de liberar a receita.

**Medicamentos de rotina:** a lista é da clínica e vale para todos os médicos. O gestor a mantém em
`/consultorio/medicamentos` (tela "Medicamentos de rotina"), que o perfil Gestor já recebe. Para outro
usuário, inclua a tela nas telas extras em Usuários. Na subida da aplicação (e, se a subida falhar nessa
etapa, no primeiro acesso), cada conta habilitada recebe uma vez a lista padrão do
sistema (`db/atendimento/medicamentos-rotina.csv`): cerca de 130 genéricos comuns da atenção primária, com
apresentação, posologia usual de adulto e via. A lista padrão não traz controlados como benzodiazepínicos e
opioides, nem doses pediátricas por peso. A clínica inclui, altera ou exclui itens. Medicamento e
apresentação não se repetem. "Restaurar lista padrão" inclui de novo os itens padrão que faltam, sem mexer nos
alterados. Excluir todos os itens não faz a lista padrão voltar sozinha. O log da subida mostra
"medicamentos de rotina: lista padrão gravada na conta <id>" na conta que recebeu a lista. Para carregar à
mão (ex.: conta nova sem reiniciar a aplicação), rode `deploy/sql/medicamentos-rotina-carga.sql` no banco,
trocando a conta nas duas linhas marcadas. O script pode rodar mais de uma vez e não altera item que a clínica
já tem.

Enquanto o atendimento está `EM_CONSULTA`, o registro nasce como rascunho e passa a valer ao liberar.
Rascunho se altera até a liberação. Registro liberado se corrige por inativação, com justificativa, e
continua no histórico. Fora de `EM_CONSULTA` o sistema recusa gravar anamnese, evolução, CID ou receita.

1. **Iniciar:** inicie a consulta de um atendimento `AGUARDANDO`. O atendimento passa a `EM_CONSULTA`, com o
   horário de início e o usuário, e o agendamento também passa a `EM_CONSULTA`. O médico inicia o atendimento
   do próprio profissional. Com outra consulta desse profissional ainda `EM_CONSULTA`, o sistema recusa até
   ela terminar.
2. **Nota da consulta:** a tela organiza a anamnese em quatro blocos, na ordem do método SOAP, com nomes do
   dia a dia. "Queixa e história": queixa principal, história da doença atual, medicamentos em uso,
   antecedentes e hábitos. "Exame físico". "Avaliação e diagnóstico": impressão clínica e CID. "Conduta e
   prescrição": conduta, receitas e nota complementar. A impressão da consulta usa os mesmos títulos. O rascunho grava
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

**Prontuário completo do paciente:** "Consultar paciente" na fila do consultório abre `/consultorio/paciente`,
e "Prontuário completo" na coluna de consultas anteriores abre a mesma tela já no paciente. A busca é pelo CPF.
A tela mostra as alergias e todos os atendimentos do paciente na clínica, de todos os médicos: nota da
consulta, CIDs, notas complementares, receitas e alta, junto com a linha do tempo da recepção (agendamentos,
chegadas, chamadas com o local, faltas) e os eventos clínicos. Só entram registros liberados e ativos; o
rascunho de outro médico não aparece. A coluna de consultas anteriores do prontuário continua mostrando só as
consultas do próprio médico. Paciente que não tem atendimento com o médico no dia pede o motivo do acesso
(até 500 caracteres). O motivo fica na auditoria (`PRONTUARIO_COMPLETO`, coluna `ds_motivo`) com usuário,
IP e horário. Paciente da fila do dia abre sem motivo.

## Propriedades

| Propriedade | Padrão | Uso |
|---|---|---|
| `atendimento.enabled` | `false` | Liga o fluxo clínico (fila, chegada e consultório). Desligada, a agenda atual segue sem este fluxo |
| `atendimento.receita.validade-dias` | `30` | Dias de validade gravados ao liberar a receita |
| `mandalore.clientes.<id>.agenda` | já existe | `mandalore` habilita chegada e consultório na conta. Outro valor: as rotas dessa conta respondem 404 |
| `mandalore.clientes.<id>.nome` | `admin.nome` | Nome da clínica na impressão da receita e no painel da TV. Sem esta propriedade, usa `admin.nome` |
| `mandalore.clientes.<id>.painel.chave` | vazio | Chave do painel de chamada da TV, com no mínimo 16 caracteres. Vazia: o painel não existe na conta |
| `mandalore.clientes.<id>.whatsapp` | vazio | Número do WhatsApp da clínica com DDI e DDD, só dígitos (ex.: `5547999990000`). Liga o QR do check-in. O código do dia deriva do segredo do webhook (`mandalore.clientes.<id>.webhook-secret` ou `chatwoot.webhook-secret`), que precisa ter no mínimo 16 caracteres |
| `mandalore.clientes.<id>.painel.locais` | vazio | Locais oferecidos ao chamar, separados por vírgula (ex.: `Consultório 1,Consultório 2,Recepção,Financeiro`). Item com mais de 60 caracteres é ignorado. Vazio: a tela só oferece digitar o local |

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
  atendimento, ação, IP e horário. A consulta pelo CPF grava `FICHA_PACIENTE` (recepção) ou
  `PRONTUARIO_COMPLETO` (médico, com o motivo quando o paciente não está na fila dele no dia).
- O check-in pelo WhatsApp exige o código do dia (HMAC do dia com o segredo do webhook, 6 caracteres) e o
  CPF com a data de nascimento. A resposta traz só o primeiro nome, o horário e o profissional. O CPF não vai
  para o log. A espera pelo CPF fica na memória da instância por 10 minutos.
- Atestado é documento do médico do atendimento: a recepção emite só a declaração de comparecimento e não
  abre atestado. O CID no atestado exige a autorização do paciente. O evento `ATESTADO_EMITIDO` fica fora da
  linha do tempo da recepção.
- A consulta pelo CPF da recepção traz só a linha do tempo administrativa. O prontuário de todos os médicos é
  só para usuário vinculado a profissional, e só com registros liberados.
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
4. Peça a um médico da clínica para revisar a lista em `/consultorio/medicamentos` (posologias e
   apresentações) antes do uso.
5. Ligue `atendimento.enabled=true` depois do vínculo e do conselho. Com `false`, chegada e consultório
   permanecem desligados e a agenda atual segue sem este fluxo.
6. Para o check-in pelo WhatsApp, cadastre `mandalore.clientes.<id>.whatsapp` com o número que atende a
   conta no Chatwoot e confira se o segredo do webhook tem no mínimo 16 caracteres. Abra
   `/atendimento/checkin` e faça um check-in de teste com um agendamento de hoje.
7. Treine a recepção na fila, na chegada e no desfazer, e o médico em iniciar, registrar, imprimir e
   finalizar. A receita e a folha da consulta desta versão seguem para impressão e assinatura manual, e a
   clínica guarda as vias assinadas.

## O que esta versão deixa para depois

Troca de médico no meio do atendimento, sinais vitais, modelos de texto da clínica (hoje cada médico tem os
seus), cancelamento de atestado emitido, pedido de exame, catálogo de medicamentos
e de CID, assinatura digital, PDF da receita, reabrir atendimento finalizado, sala ou consultório no
painel da TV, aviso ao vivo entre várias instâncias e faturamento. Clínica com agenda em outro sistema (por exemplo `tasy`) segue
com o fluxo clínico lá.
