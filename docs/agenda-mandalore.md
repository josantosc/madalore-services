# Agenda do Mandalore para clínicas sem integração

Para clínicas pequenas cujo sistema ainda não tem integração com o Mandalore. O bot marca, confirma, cancela e
remarca consultas numa agenda própria, no PostgreSQL do Mandalore. A recepção usa a tela `/agenda` para atender no
balcão e no telefone, e para repetir no sistema da clínica cada alteração feita pelo bot.

Enquanto não houver integração, **a agenda oficial continua no sistema da clínica**. A agenda do Mandalore serve
para o bot oferecer horários e para a recepção não esquecer nada: toda alteração que ainda não foi feita lá vira uma
**pendência**, com prazo, até a recepção marcar como feita.

## Como funciona

| Quem faz | Onde | O que acontece |
|---|---|---|
| Paciente pelo bot | WhatsApp, site | Grava na agenda do Mandalore e cria a pendência. A conversa no Chatwoot recebe uma nota privada com os dados para lançar, a etiqueta `lancar-sistema` e a equipe de agendamento |
| Recepção pelo balcão ou telefone | Tela `/agenda` | Grava na agenda do Mandalore. Se a recepção já lançou no sistema da clínica, informa o código de lá e não fica pendência |
| Recepção direto no sistema da clínica | Sistema da clínica | Use "Ocupar horário" na tela `/agenda` para o bot não oferecer o mesmo horário |
| Recepção resolve a pendência | Aba Pendências | Copia o texto, lança no sistema da clínica e clica em "Feito no sistema", informando o código de lá |

A etiqueta `lancar-sistema` sai da conversa quando todas as pendências dela forem resolvidas. A equipe fica, como
registro de quem cuidou.

A conversa **continua pendente**, com o bot respondendo: se o paciente voltar para cancelar ou remarcar, o bot
atende. Por isso ela não aparece em "Minhas" nem em "Não atribuídas", que mostram as abertas. Crie no Chatwoot uma
**visão salva** com equipe de agendamento + etiqueta `lancar-sistema` e fixe na barra lateral: é a fila da recepção.

O bot só oferece consultas, e só nos horários da grade marcados como "Oferece no bot", com a antecedência mínima
configurada. Exames ainda não são marcados por esta agenda. Quando o paciente procura uma consulta
marcada por telefone que não está na agenda do Mandalore, o bot oferece chamar alguém da equipe.

## Rotina da recepção

1. **Ao chegar:** abra `/agenda`, aba Pendências. Resolva as atrasadas primeiro (aparecem em vermelho).
2. **Durante o dia:** quando chegar a nota privada no Chatwoot ou a etiqueta `lancar-sistema`, abra o link da nota.
   Ele leva direto à pendência. Lance no sistema da clínica e clique em "Feito no sistema".
3. **Marcou pelo telefone no sistema da clínica:** use "Ocupar horário" na agenda do Mandalore, ou marque também
   nele informando o código do sistema da clínica.
4. **Fim do dia:** aba Conferência. Compare as consultas de amanhã com o sistema da clínica e marque "Igual" ou
   "Diferente". Cada "Diferente", com a descrição do que está diferente, vira pendência para corrigir.

Se marcou "Feito no sistema" por engano, use "Reabrir" na pendência.

## Regras das pendências

| Alteração na agenda do Mandalore | Pendência criada |
|---|---|
| Marcação sem código do sistema da clínica | Lançar |
| Marcação com código (a recepção já lançou) | Nenhuma |
| Cancelamento antes de lançar | A pendência de lançar é descartada. Nada a fazer no sistema da clínica |
| Cancelamento depois de lançado | Cancelar, com o código do sistema da clínica |
| Cancelamento de remarcação ainda não lançada | Cancelar o agendamento original, que é o que está no sistema da clínica |
| Remarcação antes de lançar | Lançar no horário novo |
| Remarcação depois de lançado | Remarcar, com "De", "Para" e o código do sistema da clínica |
| Confirmação | Nenhuma. Com `AGENDA_PENDENCIA_CONFIRMACAO=true`, pede para marcar como confirmado no sistema da clínica |
| Conferência "Diferente" | Conferir divergência, com a descrição |
| A recepção informa "já fiz no sistema da clínica" | Nenhuma |

Para concluir uma pendência de lançar ou de remarcar, o código do sistema da clínica é obrigatório. Ele fica
gravado no agendamento e aparece nas pendências seguintes (cancelar, remarcar).

Cada pendência tem prazo (`AGENDA_PRAZO_MINUTOS`, padrão 30 minutos). Depois dele, aparece como atrasada na tela,
no relatório da agenda e no resumo da curadoria.

Toda alteração fica no histórico do agendamento, com quem fez e quando.

## Segurança

- Cada clínica só enxerga a própria agenda. A conta do Chatwoot é a chave de separação no banco.
- O login da recepção de cada clínica (`mandalore.clientes.<id>.recepcao`) só abre a tela `/agenda` daquela conta.
  O administrador da central vê todas.
- A mesma operação repetida pelo bot (mensagem reenviada, nova tentativa) não grava em dobro.
- Dois agendamentos do mesmo profissional no mesmo horário são recusados, mesmo com bot e recepção ao mesmo tempo.

## Checklist para ligar numa clínica

1. PostgreSQL da trilha ligado (`OBSERVABILITY_POSTGRES_ENABLED=true`). O schema `agenda` é criado na subida.
2. No Chatwoot de cada conta: crie a etiqueta `lancar-sistema` em Configurações → Etiquetas (sem ela, a tela do
   Chatwoot não mostra a etiqueta), a equipe de agendamento (ou use a Recepção) e a visão salva descrita acima.
3. No Coolify: `CHATWOOT_EQUIPE_AGENDAMENTO` com o id da equipe (vazio usa `CHATWOOT_EQUIPE_RECEPCAO`),
   `AGENDA_ENABLED=true` e `AGENDA_URL` com o endereço público do Mandalore (sem barra no fim), para o
   link da nota privada. Sem ele, vale `RELATORIOS_URL`.
4. Para a clínica usar a agenda do Mandalore no bot, no `clientes.properties`:
   `mandalore.clientes.<account_id>.agenda=mandalore`. Numa instalação de uma clínica só, basta
   `AGENDA_ORIGEM=mandalore`.
5. Login da recepção da clínica, no `clientes.properties`: `mandalore.clientes.<account_id>.recepcao.usuario` e
   `.recepcao.senha`. A senha vai no cofre de senhas, nunca no repositório.
6. Na tela `/agenda`, aba Cadastros: profissionais, especialidades, convênios e a grade semanal de cada profissional,
   marcando quais horários o bot pode oferecer. Use os bloqueios para férias e feriados.
7. Teste no simulador: marque, cancele e remarque uma consulta e veja as pendências e a nota no Chatwoot.
8. Treine a recepção na rotina acima.
9. No OpenClaw, agende o relatório `agenda` (ver abaixo).

## Variáveis

| Variável | Padrão | Uso |
|---|---|---|
| `AGENDA_ENABLED` | `false` | Liga a tela, a API e a agenda do bot |
| `AGENDA_ORIGEM` | `tasy` | `mandalore` faz o bot usar esta agenda nas contas sem `mandalore.clientes.<id>.agenda` |
| `AGENDA_CONTA_PADRAO` | `CHATWOOT_ACCOUNT_ID` | Conta usada fora de uma conversa do Chatwoot (simulador, Twilio) |
| `AGENDA_USER` / `AGENDA_PASSWORD` | vazio | Login que só abre a agenda, de todas as contas. Opcional |
| `AGENDA_PRAZO_MINUTOS` | `30` | Prazo de cada pendência |
| `AGENDA_ANTECEDENCIA_MINUTOS` | `60` | Antecedência mínima dos horários oferecidos pelo bot |
| `AGENDA_PENDENCIA_CONFIRMACAO` | `false` | Cria pendência quando o paciente confirma |
| `AGENDA_URL` | `RELATORIOS_URL` | Endereço público usado no link da nota privada |
| `CHATWOOT_EQUIPE_AGENDAMENTO` | `CHATWOOT_EQUIPE_RECEPCAO` | Id da equipe do Chatwoot que recebe as conversas com pendência |

Por clínica, no `clientes.properties`: `mandalore.clientes.<id>.agenda=mandalore|tasy` e
`mandalore.clientes.<id>.recepcao.usuario` / `.senha`.

## OpenClaw

O relatório `agenda` (`GET /openclaw/relatorios/agenda`) traz as pendências abertas, as atrasadas, as divergências
da conferência e as consultas de amanhã ainda não conferidas, com o link da tela. Agende no meio da manhã e no fim da
tarde, nas clínicas que usam a agenda do Mandalore. O resumo diário da curadoria também mostra a linha da agenda.

## Quando a integração ficar pronta

Troque `mandalore.clientes.<id>.agenda` para `tasy` (ou o adaptador da integração). Antes, resolva todas as
pendências e confira os agendamentos futuros. A agenda do Mandalore fica no banco como histórico.
