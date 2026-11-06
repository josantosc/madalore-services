# Funil de orçamento

**Situação: primeira versão feita.** Faz parte da Fase 1 (relacionamento proativo) do [`roadmap.md`](roadmap.md) e
entra na faixa de 30 a 90 dias do [`betateste-escopo.md`](betateste-escopo.md). O que ainda falta está em
[Próximos passos](#próximos-passos).

O paciente pede um orçamento, recebe o valor e some. Hoje ninguém acompanha o que aconteceu depois. O funil abre
uma **oportunidade** para cada orçamento, mostra ao atendente quem precisa de retorno no dia e fecha sozinho quando
o paciente marca pelo assistente. É o pedaço de CRM que a clínica precisa, dentro do Mandalore e do Chatwoot.

## Por que não um CRM pronto

- O Tasy já é o cadastro do paciente e da agenda. Um CRM teria uma segunda base de pacientes, que diverge.
- Cada sistema com dado de saúde é mais um ponto no relatório de impacto (LGPD) e no contrato de operador.
- O Mandalore já tem o que um CRM precisaria integrar: Chatwoot, Tasy, orçamento por foto e trilha.
- O atendente continua no Chatwoot. Não há outro sistema para a recepção aprender.

CRM pronto (Twenty ou EspoCRM) volta a fazer sentido em venda para empresas (medicina ocupacional, contrato
corporativo), com vendedor externo e proposta formal. Ele fica ao lado do Mandalore, não no lugar do funil.

## Como funciona

### De onde vem a oportunidade

| Origem | Como abre |
|---|---|
| Orçamento por foto | A conversa vai para o Faturamento: pedido lido (com total e quantidade de exames), exames não identificados ou leitura que falhou (sem valor). O Mandalore põe a etiqueta `orcamento` na conversa |
| Comando `/orcamento` | O atendente escreve uma **nota privada** `/orcamento hemograma, glicose, TSH`. O Mandalore calcula pela tabela de preços, responde em nota privada e abre (ou atualiza) a oportunidade com total e quantidade de exames. Põe a etiqueta `orcamento` |
| Orçamento manual | O atendente **adiciona** a etiqueta `orcamento` na conversa (valor passado por texto, áudio ou telefone); o valor entra pela tela |

A etiqueta `orcamento` no Chatwoot quer dizer "esta conversa está no funil", qualquer que seja a origem. A abertura
pela foto não depende dela: se o Chatwoot recusar a etiqueta, a oportunidade continua aberta. A volta da etiqueta
pelo webhook não abre outra oportunidade, porque a conversa já tem uma aberta. Tirar a etiqueta não fecha a
oportunidade; feche na tela.

Imagem que não é pedido médico vai para a Recepção e não abre oportunidade. Uma oportunidade aberta por conversa:
novo orçamento na mesma conversa atualiza o valor da que está aberta. A etiqueta que já estava na conversa não
reabre uma oportunidade fechada; só conta quando acaba de ser adicionada.

### Valor

O valor vem da tabela de preços (`/orcamento`), a mesma do orçamento por foto, de três jeitos:

- **Foto do pedido:** soma dos exames lidos que têm preço.
- **Comando `/orcamento`:** o atendente lista os exames numa nota privada, separados por vírgula, ponto e vírgula
  ou linha. Cada termo casa por código, nome ou sinônimo, sem acento e sem diferença de maiúscula; sem igualdade,
  vale o único item da tabela que contém o termo ("hemograma" acha "Hemograma completo"). Termo que casa com mais
  de um item fica sem preço, com até três sugestões da tabela na nota. A nota de resposta traz o texto pronto para
  o paciente e o total. Mandar o comando de novo com a lista certa troca o valor.
- **Tela:** clicar no valor (ou em "Informar valor") no quadro ou na lista, para orçamento passado por áudio ou
  telefone, desconto ou correção. Não muda a quantidade de exames.

Exame sem preço não entra no total. O comando só vale em nota privada de atendente que começa com `/orcamento`
(ou `/orçamento`); a resposta do Mandalore também é nota privada, então o paciente não vê nada. Precisa de
`ORCAMENTO_ENABLED=true`. Cada troca de valor fica no histórico (`funil.evento`, detalhe `VALOR 37.00 EXAMES 3`).

### Etapas

| Etapa | Entra quando | Próximo retorno |
|---|---|---|
| Novo | A oportunidade abre | Hoje |
| Orçamento enviado | O atendente manda a primeira mensagem ao paciente com a conversa aberta | 2 dias úteis |
| Em negociação | O paciente responde depois do orçamento enviado | 1 dia útil |
| Agendado (ganho) | O assistente marca nessa conversa depois da abertura, ou o atendente marca na tela | — |
| Perdido | O atendente marca na tela, com o motivo, ou passam 15 dias sem mensagem do paciente | — |

Cada nova mensagem do atendente empurra o retorno (2 dias úteis em Orçamento enviado, 3 em Em negociação). A
resposta do bot não conta: ela sai com a conversa pendente, e só vale mensagem com a conversa aberta, não privada e
que não seja de agent bot.

Na tela dá para mudar qualquer etapa, inclusive reabrir uma fechada (o retorno volta para hoje). Motivos de perda:
achou caro, convênio não atendido, fez em outro lugar, sem horário, desistiu e não respondeu.

### Fechar sozinho

- **Agendado:** de hora em hora, a rotina procura na trilha um agendamento gravado pelo assistente
  (`RESULTADO_SUCESSO_ESCRITA` com `escrita=agendar`) na mesma conversa, depois da abertura.
- **Perdido:** sem mensagem do paciente há `FUNIL_DIAS_SEM_RESPOSTA` dias (padrão 15), motivo "não respondeu".

O agendamento feito pela recepção, por telefone ou no balcão não fecha sozinho nesta versão: o atendente marca
Agendado na tela.

### Retorno ao paciente

Às 8h, de segunda a sábado, a rotina pega as oportunidades abertas com retorno até o dia, deixa uma nota privada na
conversa, atribui a equipe Faturamento e abre a conversa. Cada oportunidade recebe no máximo um aviso por dia.

```
Retorno do orçamento: orçamento enviado, sem resposta do paciente.
Valor: R$ 37,00 (2 exames).
Última mensagem do paciente há 3 dias.
Até 24 horas depois da última mensagem do paciente dá para escrever aqui. Depois disso, ligue.
Quando fechar, marque Agendado ou Perdido no funil: https://bs-api.jscinfor.com/funil
```

O retorno é sempre do atendente, nunca automático. Fora da janela de 24 horas do WhatsApp não há template nesta
versão: o atendente liga.

### Tela

`/funil`, dentro da central de administração (`/admin`):

- **Números:** em aberto (quantidade e valor), retornos do dia (e atrasados), agendados e conversão dos últimos 30
  dias.
- **Hoje:** retornos do dia e atrasados, com link para a conversa no Chatwoot e os botões Agendado, Perdido e Adiar
  retorno.
- **Quadro:** uma coluna por etapa, com o valor total. Arrastar o cartão muda a etapa; soltar em Perdido pede o
  motivo. Mostra as abertas e as fechadas nos últimos 30 dias.

A tela se atualiza a cada minuto.

## Configuração

### Coolify (Mandalore)

```
FUNIL_ENABLED=true
```

Requer `OBSERVABILITY_POSTGRES_ENABLED=true` e `OBSERVABILITY_POSTGRES_INITIALIZE_SCHEMA=true` (cria o schema
`funil`). O orçamento por foto e o comando `/orcamento` (`ORCAMENTO_ENABLED`) são opcionais: sem eles, só a
etiqueta abre oportunidade e o valor entra pela tela.

O `ADMIN_USER` entra na tela. `FUNIL_USER` e `FUNIL_PASSWORD` são opcionais: um login que só abre o funil, para
quem não deve ver as outras telas da central.

Opcionais:

| Variável | Padrão | Uso |
|---|---|---|
| `FUNIL_DIAS_SEM_RESPOSTA` | `15` | Dias sem mensagem do paciente para fechar como perdida |
| `FUNIL_RETORNO_CRON` | `0 0 8 * * MON-SAT` | Horário dos retornos do dia (cron do Spring, horário de Brasília) |
| `FUNIL_FECHAMENTO_CRON` | `0 10 * * * *` | Fechamento automático (agendadas e sem resposta) |
| `FUNIL_URL` | `RELATORIOS_URL` | Endereço público do Mandalore para o link na nota de retorno |

### Chatwoot

1. No webhook do Mandalore (Configurações > Integrações > Webhooks), marcar também **Conversa atualizada**
   (`conversation_updated`), além de **Mensagem criada**. Sem ele, a etiqueta `orcamento` não abre oportunidade.
2. Criar a etiqueta `orcamento` (Configurações > Etiquetas).
3. Conferir se `CHATWOOT_EQUIPE_FATURAMENTO` está preenchida: é a equipe que recebe os retornos.

## Dados

Tabela `funil.oportunidade`, uma linha por oportunidade:

| Coluna | Uso |
|---|---|
| `conta_id`, `conversa_id` | conversa de origem no Chatwoot |
| `origem` | `ORCAMENTO_FOTO` ou `ORCAMENTO_MANUAL` |
| `etapa`, `motivo_perda` | situação atual |
| `valor`, `qtd_itens` | total e quantidade de exames (foto ou `/orcamento`); o valor também pode ser informado na tela |
| `paciente` | nome do contato no Chatwoot, atualizado a cada mensagem ou mudança na conversa |
| `responsavel` | atendente atribuído à conversa no Chatwoot; sem atribuição, o último atendente que respondeu |
| `proxima_acao`, `retorno_avisado_em` | dia do retorno e dia em que a rotina avisou |
| `ultima_resposta_em` | última mensagem do paciente, para o prazo de perda |
| `aberta_em`, `fechada_em` | ciclo |

Índice único parcial em (`conta_id`, `conversa_id`) para oportunidade aberta. Tabela `funil.evento` com cada
mudança de etapa (anterior, nova, quem mudou e quando).

**LGPD.** Mesma regra do orçamento por foto: o funil não grava o nome dos exames, a foto, o texto da conversa, o
CPF nem o telefone. Os exames do `/orcamento` ficam só nas notas privadas do Chatwoot. O detalhe fica no Chatwoot; o funil guarda valor, quantidade, as chaves e o nome do contato
(o mesmo que aparece no Chatwoot), para o atendente reconhecer o paciente na tela.

O nome e o responsável vêm do `meta` da conversa no webhook (`meta.sender.name` e `meta.assignee.name`). A
oportunidade aberta antes desta mudança ganha os nomes no próximo evento da conversa (mensagem, atribuição ou
etiqueta).

## Fora do escopo

Campos personalizados, vários funis configuráveis, proposta em PDF, e-mail marketing, previsão de vendas,
permissões por registro e importação de leads. Se a clínica precisar disso, é sinal de CRM pronto ao lado.

## Próximos passos

1. **Agendado pela recepção.** Fechar sozinho quando houver agendamento no Tasy para o paciente: pelo CPF
   informado na conversa ou pelo celular do contato do Chatwoot. Antes, levantar no Tasy onde fica o celular
   confiável do paciente. Se casar mais de um paciente, não fecha.
2. **Painel na conversa.** A oportunidade como Dashboard App do Chatwoot (painel lateral), com os botões Agendado
   e Perdido. Conferir se o cookie de sessão funciona dentro do painel (domínios diferentes, `SameSite=None`); se
   não, um token curto por conversa.
3. **Template de retorno** de utilidade na Meta ("Seu orçamento de exames continua válido. Quer agendar?"), para
   escrever fora da janela de 24 horas.
4. **Retomada como origem.** O abandono de prioridade alta da [retomada](retomada-agendamento.md) abre oportunidade,
   quando a retomada existir.
5. **Relatório.** Conversão, receita convertida e motivos de perda no relatório quinzenal e no OpenClaw, sem dado
   de paciente.
6. **Clínicas.** Feito pelos [usuários da central](usuarios-perfis.md): os perfis Recepção, Faturamento e Gestor
   veem o funil só das clínicas deles. O login antigo da recepção (`mandalore.clientes.<id>.recepcao`) continua só
   com a agenda.

## Indicadores

- **Conversão:** orçamentos que viraram agendamento, por origem e por responsável.
- **Receita convertida:** soma do valor das oportunidades agendadas. Junto com a receita recuperada da retomada, é
  o número que vende o módulo Relacionamento.
- Tempo do orçamento ao agendamento.
- Motivos de perda, para a clínica rever preço, convênio ou agenda.
- Retornos atrasados por responsável.
