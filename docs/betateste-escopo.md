# Betateste: o que entra e o que fica para depois

O Mandalore será implantado primeiro numa clínica parceira, que funciona como **clínica-laboratório**: tudo o que está no [`roadmap.md`](roadmap.md) pode ser testado nela antes de ser vendido
a outras clínicas. Este documento define a ordem de entrada. Situação em 06/11/2026.

## Princípios

1. **Escopo aberto.** Não há aditivo por funcionalidade. A ordem é combinada com a clínica e revista a cada
   quinze dias.
2. **Retorno financeiro primeiro.** Entra antes o que reduz faltas, recupera agendamentos ou tira volume da
   recepção.
3. **Medir desde o início.** Os números da clínica viram o caso de sucesso para vender às próximas. Sem linha de
   base não há caso.
4. **Risco baixo.** Item com dado sensível de saúde ou mudança na forma de gravar no Tasy só entra com o restante
   estável.
5. **Chatwoot desde o início.** Bot e atendentes no mesmo número de WhatsApp.

## Resumo

| Quando | Item | Situação no produto |
|---|---|---|
| Go-live | Marcar consulta e exame, com preparo do exame | Pronto |
| Go-live | WhatsApp oficial no Chatwoot | Parcial |
| Go-live | Transbordo, equipes, respostas prontas e horário de atendimento | Pronto, falta configurar |
| Go-live | Ver, confirmar, cancelar e remarcar pelo WhatsApp | Pronto |
| Go-live | Perguntas frequentes | Pronto, faltam os textos |
| Go-live | Etapas do agendamento na trilha | A fazer |
| Até 30 dias | Lembrete D-1 com confirmação | A fazer |
| Até 30 dias | Preparo de exame para quem marcou pela recepção | A fazer (junto com o lembrete) |
| Até 30 dias | Orçamento por foto do pedido | Pronto, falta a tabela de preços |
| Até 30 dias | Pesquisa de satisfação (CSAT) | Configuração do Chatwoot |
| 30 a 90 dias | Retomada de agendamento | A fazer |
| 30 a 90 dias | Lista de espera e encaixe | A fazer |
| 30 a 90 dias | Funil de orçamento | Pronto, falta ligar e configurar o webhook |
| 30 a 90 dias | Convite de avaliação no Google | A fazer |
| Depois | Itens que dependem de maturidade ou de LGPD | Abaixo |

## Go-live

**Marcar consulta e exame.** No exame, o paciente recebe a orientação de preparo do Tasy e as orientações gerais
da clínica.

**WhatsApp oficial no Chatwoot.** Caixa WhatsApp com o bot `bot-agenda` e os atendentes. Provedor: Twilio ou
WhatsApp Cloud API da Meta. Detalhes em [`chatwoot.md`](chatwoot.md).

**Transbordo, equipes, respostas prontas e horário de atendimento.** A conversa que o bot não resolve vai para
Recepção, Laboratório ou Faturamento. Fora do horário, a mensagem de ausência avisa que o assistente continua
marcando. Detalhes em [`manual-atendimento-equipes.md`](manual-atendimento-equipes.md).

**Ver, confirmar, cancelar e remarcar.** Já existe no bot. Entra no go-live porque o lembrete D-1 depende dele: o
paciente que não pode ir remarca sozinho, sem ligar.

**Perguntas frequentes.** Endereço, horário, convênios, preparo, documentos, estacionamento e contato. Detalhes em
[`perguntas-frequentes.md`](perguntas-frequentes.md).

**Etapas do agendamento na trilha.** Registra até onde o paciente chegou, sem fala nem dado do paciente. Mostra
onde ele desiste e prepara a retomada.

## Até 30 dias

**Lembrete D-1 com confirmação.** Item de maior retorno financeiro: reduz faltas e devolve à agenda os horários
cancelados. Depende do template de utilidade aprovado pela Meta. Começar por uma agenda e ampliar depois de duas
semanas. Detalhes no item 3 do [`roadmap.md`](roadmap.md).

**Preparo de exame para quem marcou pela recepção.** Vai junto com o lembrete de exame. Jejum longo vai na véspera,
antes do jantar.

**Orçamento por foto do pedido.** O Faturamento recebe no Chatwoot o orçamento sugerido a partir da foto do pedido.
O bot nunca envia valor ao paciente. Detalhes em [`orcamento-pedido.md`](orcamento-pedido.md).

**Pesquisa de satisfação (CSAT).** Nota ao fim do atendimento humano, ligada na caixa do Chatwoot.

## De 30 a 90 dias

**Retomada de agendamento.** Começa com uma mensagem por conversa, dentro de 24 horas. A segunda mensagem e a
classificação de motivo por IA entram depois de medir a versão simples. Detalhes em
[`retomada-agendamento.md`](retomada-agendamento.md).

**Lista de espera e encaixe.** O horário liberado pelo lembrete ou pelo cancelamento vai para o primeiro da fila.

**Funil de orçamento.** Cada orçamento vira uma oportunidade com responsável e data de retorno, e fecha sozinho
quando o paciente marca pelo assistente. O que a recepção marcar, o atendente fecha na tela `/funil`. Rende mais
com o orçamento por foto em produção. Começa sem template de retorno: o atendente escreve dentro da janela de 24
horas ou liga. Detalhes em [`funil-orcamento.md`](funil-orcamento.md).

**Convite de avaliação no Google.** Nota 4 ou 5 na pesquisa recebe o link do Perfil da Empresa. Nota baixa vai para
a Recepção.

## Depois

| Item | Motivo |
|---|---|
| Resultado de exame pelo bot | Exige relatório de impacto (LGPD) e aprovação do encarregado de dados |
| Resumo do transbordo e copiloto do atendente por IA | Fase 2 do roadmap; primeiro estabilizar o atendimento híbrido |
| Áudio e botões nativos do WhatsApp | Fase 2 do roadmap |
| Motivo de cada conversa e relatórios por clínica | Fase 3; faz sentido com mais de uma clínica |
| Migrar a equipe para usuários próprios e apagar os logins das variáveis | A tela `/usuarios` já existe; no betateste os logins atuais continuam valendo. Ver [`usuarios-perfis.md`](usuarios-perfis.md) |
| Esqueci a senha por e-mail | Depende de envio de e-mail (SMTP); até lá o gestor reseta pela tela `/usuarios` |
| Status de autorização e guia de convênio | Falta levantar onde o Tasy guarda o status |
| Cadastro de paciente novo no Tasy | Grava em `PESSOA_FISICA`; exige validação maior |
| Agente aberto e voz | Fase 5 do roadmap |

## Indicadores do caso de sucesso

Linha de base dos 3 meses anteriores ao go-live, fornecida pela clínica:

| Indicador | Fonte |
|---|---|
| Consultas e exames marcados pelo WhatsApp | Tasy (`NM_USUARIO = 'MANDALORE'`) |
| Taxa de conclusão do agendamento | Trilha |
| Marcações fora do horário comercial | Tasy |
| Faltas, antes e depois do lembrete | Tasy |
| Horários cancelados que foram reocupados | Tasy e trilha |
| Agendamentos recuperados pela retomada | Trilha |
| Orçamentos que viraram agendamento e receita convertida | Funil |
| Ligações e mensagens à recepção | Clínica |
| Tempo da primeira resposta humana e nota de satisfação | Chatwoot |

Relatório quinzenal para a clínica. Ao fim de 90 dias, o relatório consolidado vira o material comercial, com
autorização da clínica e sem dado de paciente.

## O que a clínica providencia

1. Número oficial aprovado na Meta e escolha do provedor (Twilio ou WhatsApp Cloud API).
2. Atendentes por equipe e horário de atendimento.
3. Textos das perguntas frequentes, a partir de [`deploy/faq.exemplo.yaml`](../deploy/faq.exemplo.yaml).
4. Tabela de preços e prazos dos exames.
5. Aprovação do template do lembrete e escolha da primeira agenda.
6. Linha de base dos 3 meses anteriores ao go-live.
7. Ponto focal na recepção para relatar problemas e sugestões.

## Pendências contratuais

1. **Contrato com a clínica** atualizado para este escopo. Define a clínica como controladora e o fornecedor do
   Mandalore como operador dos dados (LGPD), e os custos de terceiros (Meta, Twilio, Google, servidores).
2. **Confidencialidade entre clínicas.** Os dados de cada clínica ficam acessíveis só a ela.
