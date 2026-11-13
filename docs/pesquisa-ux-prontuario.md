# Pesquisa de UX: telas de recepção (`/atendimento`) e consultório (`/consultorio`)

Objetivo: comparar as telas atuais com os padrões de mercado de software para clínicas e propor direções
para a decisão. Base da análise: o código das telas (`src/main/resources/atendimento/index.html` e
`src/main/resources/consultorio/index.html`), a regra descrita em `docs/atendimento-clinico.md` e as fontes
listadas no fim do documento.

## Resumo

- As telas atuais estão corretas do ponto de vista de regra (estados, auditoria, rascunho e liberação), mas
  são organizadas como um cadastro: uma tabela na recepção e quatro abas de formulário no consultório. O
  mercado organiza o consultório como um **espaço de trabalho da consulta**: paciente sempre visível,
  histórico ao lado, registro em uma página só e uma única ação de "finalizar e assinar".
- A maior fricção para o médico é o modelo de **liberar cada registro separadamente**. Uma consulta simples
  (anamnese, evolução, um CID e uma receita) exige cerca de 15 cliques de ação, 3 trocas de aba e 3
  liberações antes do "Finalizar", fora a digitação. Os concorrentes salvam sozinhos e assinam tudo de uma vez
  no fim.
- O maior diferencial possível é algo que concorrente nenhum tem pronto: o **bot de WhatsApp do Mandalore**.
  Ele pode fazer check-in, pré-anamnese e entrega da receita, encostando a recepção e o médico em dados que o
  paciente já mandou.
- Recomendação: adotar a direção **B (consulta em uma tela)** como base e ir incorporando a direção **C
  (WhatsApp e IA)** por fases. Detalhes na seção "Direções para decidir".

## 1. Diagnóstico das telas atuais

### Recepção (`/atendimento`)

O que já está bom: indicadores no topo (ainda não chegaram, aguardando, em consulta, atendidos), tempo de
espera com cor (âmbar a partir de 15 min, vermelho a partir de 30), filtro por profissional e por situação,
desfazer a chegada com motivo.

Fricções e lacunas:

| Ponto | Efeito na rotina |
|---|---|
| Lista em tabela, sem busca por nome ou CPF | Em horário de pico a atendente rola a lista para achar quem chegou |
| A única ação é "Registrar chegada" | Não há "chamar paciente", confirmar dados, convênio ou carteirinha na chegada |
| Atualização a cada 60 segundos | A recepção não vê na hora que o médico iniciou ou finalizou |
| Sem encaixe (paciente sem agendamento) e sem "faltou" | A atendente sai da tela e vai para a agenda (já previsto como pendência no doc) |
| Sem painel para a TV da sala de espera | O médico chama no corredor ou a recepção avisa |
| Sem prioridade legal (60+, gestante, PcD) | A fila é apenas por horário e chegada |

### Consultório (`/consultorio`)

O que já está bom: fila do próprio médico com tempo de espera, bloqueio de duas consultas simultâneas,
contador por aba com estado (rascunho em âmbar, liberado em verde), correção por inativação com
justificativa, impressão da receita com dados do conselho.

Fricções e lacunas:

| Ponto | Efeito na rotina |
|---|---|
| Ao abrir o prontuário, o cabeçalho mostra só nome, profissional e especialidade | Idade, sexo, convênio e **alergias** não ficam visíveis o tempo todo; alergia só aparece se o médico abrir a anamnese |
| Não há histórico de consultas anteriores na tela | O médico não vê a última evolução, o último CID ou a última receita do paciente (a API registra a leitura do histórico, mas a tela não exibe) |
| Quatro abas (Anamnese, Evolução, CID, Receita) | O registro fica fragmentado; o médico pula de aba em aba e perde o contexto |
| Anamnese com 8 caixas de texto empilhadas, todas do mesmo tamanho | Muita rolagem; campos pouco usados (hábitos, antecedentes familiares) pesam igual à queixa |
| Três botões por seção ("Salvar rascunho", "Salvar e liberar", "Nova") e liberação item a item | Muitos cliques; o "Finalizar" é recusado se sobrar rascunho, e o médico descobre isso só no fim |
| Sem salvamento automático | Se o navegador fechar ou a sessão cair, o texto digitado se perde |
| CID digitado à mão (código e descrição) | Erro de digitação e lentidão; não há busca por nome da doença |
| Receita com 7 campos por item, sem base de medicamentos, sem favoritos e sem "repetir receita anterior" | Uso contínuo exige redigitar tudo a cada consulta |
| Impressão abre em pop-up, assinatura manual | O navegador bloqueia o pop-up; não há envio digital ao paciente |
| Sem modelos por especialidade, frases prontas ou atalhos de teclado | Cada médico digita do zero |

Contagem aproximada de uma consulta simples hoje: Iniciar → (anamnese) digitar → Salvar e liberar → aba
Evolução → digitar → Salvar e liberar → aba CID → código, descrição → Incluir → aba Receita → 5 a 7 campos →
Salvar e liberar → Imprimir → Finalizar → motivo → Finalizar. São cerca de 15 cliques de ação e 3 trocas de
aba.

## 2. Padrões de mercado

Referências: no Brasil, iClinic (Afya), Feegow, Amplimed, ProDoctor e Memed; fora, Epic, athenaOne,
Doctolib e Phreesia; em IA, Voa Health, Abridge, Nuance DAX e Ambience.

| Padrão | Como o mercado faz | Evidência ou referência |
|---|---|---|
| Cabeçalho fixo do paciente | Faixa sempre visível com nome, idade, sexo, convênio, alergias em vermelho, condições crônicas e medicamentos em uso | Padrão em Epic e athenaOne; heurísticas de usabilidade de prontuário (reduzir a necessidade de lembrar) |
| Tudo em uma tela | Histórico, registro, prescrição e documentos na mesma tela, sem trocar de aba | Amplimed: "todas as ferramentas em uma só aba"; iClinic: fluxo direto agenda → prontuário |
| Nota em página única com seções recolhíveis | Nota SOAP ou APSO (avaliação e plano no topo) em uma página, seções que recolhem mostrando um resumo | Estudo JABFM: APSO recolhível foi mais rápido e mais preciso que SOAP tradicional; ordem padronizada reduz de 0,9 a 1,9 min por leitura |
| Visão por problema | Resumo agrupado por diagnóstico ativo, com medicamentos e exames relacionados | Estudo PMC: visão por problema reduziu tempo (173 s contra 205 s), erros (3,4% contra 7,7%) e carga cognitiva |
| Salvamento automático e uma assinatura no fim | O médico escreve, o sistema salva sozinho e "Finalizar e assinar" libera tudo junto | Padrão em iClinic, Feegow, Amplimed e Epic |
| Modelos por especialidade e frases rápidas | Formulários configuráveis por especialidade; atalhos de texto (por exemplo, digitar `.normal` expande um exame físico normal) | Amplimed e iClinic (modelos); Epic SmartPhrases |
| Busca de CID e medicamentos | Autocompletar por nome ou código, favoritos do médico, "repetir última receita" | Memed (base de medicamentos, mais de 300 integrações); Amplimed (base de medicamentos atualizada) |
| Escriba de IA ambiente | O sistema ouve a consulta e preenche um rascunho estruturado nos campos; o médico revisa e assina | Estudo JAMA (abril de 2026, 8.581 médicos): menos 16 min de documentação por dia, até 27 min em quem usa em mais de metade das consultas. Voa + ProDoctor: até 10 min por consulta. iClinic tem IA em todos os planos; Feegow no plano VIP |
| Receita digital com validade | PDF assinado com ICP-Brasil (PAdES), metadados de CRM e UF, QR Code para o validador do ITI, envio por WhatsApp ou SMS | Guia do desenvolvedor do VALIDAR (ITI); Memed |
| Quadro de fluxo na recepção | Colunas por estado (chegou → aguardando → chamado → em consulta → concluído), um clique para chamar | Domeralia, NextClia, módulos de front desk |
| Check-in antes da chegada | O paciente confirma chegada e atualiza dados pelo celular (link ou QR) | Phreesia: 85% de check-in pelo próprio paciente nas organizações de melhor desempenho; 5 min de recepção poupados por consulta |
| Painel de chamada na TV | Tela pública com nome abreviado e consultório, chamada por voz, sem dado clínico | NovaLogi Flow, NextClia, Domeralia |
| Atualização em tempo real | A recepção vê a mudança de estado do médico na hora, sem recarregar | Padrão nos quadros de fluxo citados |

## 3. Direções para decidir

### Direção A: evolução das telas atuais

Mantém a estrutura (tabela na recepção e abas no consultório) e corrige as fricções pontuais.

- Cabeçalho do paciente com idade, convênio e alergias.
- Salvamento automático do rascunho.
- Busca por nome na recepção; botão "Chamar paciente".
- Aviso antecipado dos rascunhos pendentes antes de "Finalizar".
- Anamnese com campos menos usados recolhidos.

Esforço baixo, sem mudança de regra no backend. Ganho moderado: a quantidade de cliques continua parecida,
porque a liberação item a item permanece.

### Direção B: consulta em uma tela (recomendada como base)

Reorganiza o consultório como espaço de trabalho e a recepção como quadro de fluxo.

Consultório, em três colunas:

```
+--------------------------------------------------------------------------------------+
| Maria Souza · 54 anos · Unimed · ALERGIA: dipirona · HAS, DM2 · em consulta 12:40    |
+----------------------+----------------------------------------+----------------------+
| HISTÓRICO            | CONSULTA DE HOJE (página única)        | RESUMO E AÇÕES       |
| 02/10 Retorno        | ▸ Queixa e história          [modelo]  | CID: I10 (principal) |
|   I10 · losartana    | ▸ Exame físico     [.normal]           | Receita: 2 itens     |
| 15/07 Primeira       | ▸ Avaliação e conduta                  | Retorno: 30 dias     |
|   E11 · metformina   | ▸ CID   [buscar "hipertensão"...]      |                      |
| [ver tudo]           | ▸ Receita [repetir última] [+ item]    | salvo há 3 s         |
|                      |                                        | [Finalizar e assinar]|
+----------------------+----------------------------------------+----------------------+
```

- Cabeçalho fixo com alergias em destaque.
- Histórico das consultas anteriores à esquerda; clicar em uma receita antiga permite repeti-la.
- Uma página com seções recolhíveis no lugar das abas, em ordem fixa.
- Salvamento automático; o rascunho deixa de ser uma ação do médico.
- "Finalizar e assinar" libera de uma vez anamnese, evolução, CID e receita, e abre o motivo da alta.
- Busca de CID por nome ou código (tabela CID-10 local) e favoritos de medicamentos por médico.
- Modelos por especialidade e frases rápidas.
- Atalhos de teclado (por exemplo, `Ctrl+Enter` para finalizar e `Alt+1..5` para ir às seções).

Recepção como quadro: colunas "A chegar", "Aguardando", "Em consulta" e "Atendidos", com busca, botão de
chamar, encaixe e "faltou", e atualização em tempo real (SSE no lugar do recarregamento a cada 60 s).

Esforço médio. **Muda regra de negócio**: hoje cada registro é liberado individualmente e a finalização exige
tudo liberado. Na direção B, a finalização passa a liberar os rascunhos juntos. A trilha de auditoria e a
correção por inativação continuam iguais. Precisa de validação do jurídico ou da direção clínica, porque o
documento atual trata a liberação item a item como regra.

Estimativa de ganho: uma consulta simples cai de cerca de 15 cliques de ação para 4 ou 5 (iniciar, repetir
receita ou buscar CID, finalizar e assinar, confirmar motivo).

### Direção C: WhatsApp e IA (diferencial, por fases sobre a B)

Usa o que só o Mandalore tem: o bot de agendamento no WhatsApp.

1. **Check-in pelo WhatsApp**: no dia da consulta o bot pergunta "Já chegou?". O paciente responde e entra na
   fila; a recepção apenas confere. Atende também quem chega sem agendamento.
2. **Pré-consulta pelo bot**: o paciente responde queixa, medicamentos em uso e alergias antes de chegar. O
   médico abre a consulta com esses campos já em rascunho para revisar.
3. **Painel na TV e aviso no celular**: "Maria S., consultório 2", sem dado clínico, e a mesma chamada
   enviada no WhatsApp.
4. **Receita digital**: assinatura ICP-Brasil com QR Code do VALIDAR, enviada ao paciente pelo WhatsApp. O
   caminho mais curto é integrar a Memed (widget de prescrição, sem custo de integração); o caminho próprio
   exige assinador PAdES e metadados de CRM e UF.
5. **Escriba de IA**: grava a consulta e preenche o rascunho da página única. O médico revisa e assina.
   Requer consentimento do paciente para a gravação (dado sensível pela LGPD), política de retenção do áudio
   e decisão entre parceiro (Voa Health já integra prontuários brasileiros) e solução própria.

Esforço alto e distribuído. Os itens 1 a 3 reaproveitam a infraestrutura do bot e entregam diferencial cedo.
Os itens 4 e 5 dependem de terceiros, certificado digital e definição jurídica.

### Comparação

| | A: evolução | B: uma tela | C: WhatsApp e IA |
|---|---|---|---|
| Esforço | Baixo | Médio | Alto, em fases |
| Ganho para o médico | Moderado | Alto | Muito alto |
| Ganho para a recepção | Moderado | Alto | Muito alto |
| Muda regra de negócio | Não | Sim, liberação única | Sim, mais consentimento e assinatura digital |
| Diferencial de mercado | Nenhum (paridade parcial) | Paridade com iClinic, Feegow e Amplimed | Acima do mercado (check-in e pré-consulta pelo bot) |
| Dependência externa | Nenhuma | Tabela CID-10 | Memed ou assinador, parceiro de IA, consentimento |

## 4. Decisões que dependem de você

1. **Direção**: A, B ou B com fases de C.
2. **Liberação única na finalização**: aceita trocar a liberação item a item por "Finalizar e assinar"?
3. **Formato da nota**: manter os campos atuais da anamnese, ou adotar SOAP ou APSO (avaliação e plano no
   topo) em página única?
4. **Receita digital**: integrar a Memed, construir assinatura ICP-Brasil própria, ou manter a impressão por
   enquanto?
5. **IA na consulta**: parceiro (por exemplo, Voa Health), solução própria, ou deixar para depois?
6. **Recepção**: quadro em colunas ou lista melhorada? Haverá TV na sala de espera?
7. **Check-in e pré-consulta pelo WhatsApp**: entram no primeiro ciclo?

## 5. Como medir

Medir antes e depois, com dois ou três médicos e uma recepção piloto:

- Cliques e tempo por consulta, do "Iniciar" ao "Finalizar".
- Consultas finalizadas no mesmo dia contra prontuários pendentes no fim do expediente.
- Tempo de espera do paciente e tempo da recepção por chegada.
- Questionário SUS (System Usability Scale) com médicos e atendentes.
- Erros: CID inválido, receita cancelada e chegada desfeita.

## 6. Plano escolhido: B e depois C

Decisão: implementar a direção B e evoluir para a C por fases. O B já nasce com os pontos de encaixe que a C
usa, para que cada fase da C seja ligar uma peça nova sem refazer tela nem regra.

### Situação do código hoje

| Item | Situação |
|---|---|
| Histórico do paciente | Já existe `GET /consultorio/api/pacientes/{id}/historico`, com auditoria. A tela não usa |
| Dados do paciente na consulta | `Atendimento.Ficha` traz nome, profissional, especialidade e `convenioId`. Faltam nascimento (está em `agenda.paciente`) e nome do convênio (está em `agenda.convenio`) |
| Alergias | Só no texto livre da anamnese do atendimento atual. Não há alergia "do paciente" que atravesse consultas |
| Liberação | Um endpoint de liberar por registro (anamnese, evolução, receita). `finalizar` recusa se houver rascunho |
| Chamada do paciente | Não existe. O status de `atendimento_paciente` aceita `AGUARDANDO`, `EM_CONSULTA`, `FINALIZADO` e `CANCELADO` |
| Origem do registro | Não existe. Todo rascunho é tratado como do médico |
| Atualização das telas | Recarregamento a cada 60 segundos nas duas telas |
| Receita | `impressaoReceita` monta HTML para impressão; não há geração de documento separada da saída |

### Fase B1: consultório em uma tela

Backend:

- Incluir na `Ficha` (ou num cabeçalho próprio do prontuário) a idade calculada pelo nascimento, o nome do
  convênio e as alergias da última anamnese liberada do paciente.
- Criar `POST /consultorio/api/atendimentos/{id}/finalizar-e-assinar`. Numa única transação, libera os
  rascunhos com conteúdo, descarta os vazios e finaliza com o motivo da alta. Grava um evento por registro
  liberado, como hoje. Os endpoints de liberar por registro continuam para compatibilidade.
- Tabela CID-10 local (`atendimento.cid10`, carregada na subida) e
  `GET /consultorio/api/cid?q=` com busca por código e por nome.
- Favoritos de medicamento por profissional e "repetir receita" a partir de uma receita do histórico, que cria
  um rascunho novo copiando os itens.

Tela (`consultorio/index.html`):

- Cabeçalho fixo com idade, convênio e alergias em destaque, e o tempo de consulta.
- Coluna de histórico usando o endpoint existente.
- Página única no lugar das abas, com seções recolhíveis em ordem fixa: queixa e história, exame físico,
  avaliação e conduta, CID, receita.
- Salvamento automático alguns segundos após parar de digitar, com indicação "salvo há N s". O botão de rascunho
  sai da tela.
- Botão único "Finalizar e assinar", que abre o motivo da alta e chama o endpoint novo.
- Atalhos de teclado e modelos de texto por especialidade.

Dependência: aprovação da liberação única pelo jurídico ou pela direção clínica, e atualização de
`docs/atendimento-clinico.md`.

### Fase B2: recepção em quadro e tempo real

- Colunas "A chegar", "Aguardando", "Chamado", "Em consulta" e "Atendidos", com busca por nome ou CPF.
- Chamada do paciente: colunas `dt_chamada` e `usuario_chamada` em `atendimento_paciente`, mais o evento
  `CHAMADO`. O status continua `AGUARDANDO`, para não alterar a restrição do banco nem o status do agendamento.
  O médico também pode chamar pela fila do consultório.
- "Faltou" e encaixe sem agendamento, já listados como pendências em `docs/atendimento-clinico.md`.
- Atualização em tempo real por SSE (`/atendimento/api/eventos` e `/consultorio/api/eventos`), publicando
  apenas tipo, id e status, sem conteúdo clínico. O recarregamento a cada 60 segundos fica como reserva.

### Pontos de encaixe para a C (feitos dentro do B)

| Encaixe | Onde | Usado na C por |
|---|---|---|
| Origem do rascunho (`ie_origem`: `MEDICO`, `PACIENTE`, `IA`) | Anamnese e evolução | Pré-consulta pelo bot e escriba de IA. A tela marca o que veio de fora para o médico revisar |
| Evento `CHAMADO` | Atendimento e SSE | Painel na TV e mensagem no WhatsApp |
| Chegada como caso de uso independente da tela | `AtendimentoService` | Check-in pelo bot, com a mesma regra e a mesma idempotência |
| Geração da receita separada da saída | Receita | Troca da impressão por PDF assinado e envio pelo WhatsApp |

### Fases da C

1. **Painel na TV e chamada pelo WhatsApp**: página pública por clínica, protegida por token, com nome
   abreviado e consultório, assinando o evento `CHAMADO`. O bot envia "Maria, pode entrar no consultório 2".
2. **Check-in pelo WhatsApp**: no dia da consulta, o bot pergunta se o paciente chegou e chama o caso de uso de
   chegada. A recepção apenas confere no quadro.
3. **Pré-consulta pelo bot**: queixa, medicamentos em uso e alergias gravados como rascunho de origem
   `PACIENTE`.
4. **Receita digital**: integrar a Memed ou montar assinatura PAdES ICP-Brasil própria, com QR Code do VALIDAR
   e envio pelo WhatsApp.
5. **Escriba de IA**: rascunho de origem `IA` na página única. Antes, definir consentimento do paciente para a
   gravação, retenção do áudio e parceiro ou solução própria.

### Decisões que precisam sair antes de começar

- Liberação única no "Finalizar e assinar" (bloqueia a B1).
- Formato da página única: manter os campos atuais da anamnese ou adotar SOAP ou APSO.
- Se as alergias passam a ser um dado do paciente, mantido entre consultas, e não só um texto da anamnese.

## Fontes

- [Amplimed: prontuário eletrônico](https://www.amplimed.com.br/prontuario-eletronico/)
- [Feegow ou iClinic? Comparativo 2026](https://doctoriasystem.com.br/blog/feegow-ou-iclinic/)
- [Doctte: funcionalidades de sistemas médicos](https://www.doctte.com/blog/melhores-funcionalidades-sistemas-medicos)
- [JAMA, 2026: escribas de IA reduzem tempo de documentação (resumo Telehealth.org)](https://telehealth.org/news/large-jama-study-finds-ai-scribes-cut-documentation-time-for-ambulatory-clinicians/)
- [Voa Health e ProDoctor: IA de transcrição no prontuário](https://voa.health/blog/ia-prontuario-eletronico-transcricao-consultas)
- [Ensaio SOAR (UFPR) com escriba de IA](https://clinicaltrials.gov/study/NCT07302906)
- [JABFM: nota APSO recolhível](https://www.jabfm.org/content/30/6/691)
- [PMC: leitura de notas e ordem de seções](https://pmc.ncbi.nlm.nih.gov/articles/PMC6584143/)
- [PMC: visão orientada a problemas](https://pmc.ncbi.nlm.nih.gov/articles/PMC8068438/)
- [PMC: violações de heurísticas de usabilidade em prontuários](https://pmc.ncbi.nlm.nih.gov/articles/PMC7266506/)
- [Memed: integração de prescrição](https://suporte-medico.memed.com.br/hc/pt-br/articles/53501207014427-Integra%C3%A7%C3%A3o-de-Prescri%C3%A7%C3%A3o-Memed)
- [ITI VALIDAR: guia do desenvolvedor](https://validar.iti.br/guia-desenvolvedor.html)
- [Phreesia: check-in pelo paciente](https://www.phreesia.com/specialty/health-systems/)
- [Domeralia: check-in e sala de espera](https://domeralia.com/pt-br/funcionalidades/check-in/)
- [NextClia: recepção e senha digital](https://nextclia.com.br/funcionalidades/recepcao)
- [NovaLogi Flow: totem, painel na TV e WhatsApp](https://novalogiflow.com.br/lp/)
