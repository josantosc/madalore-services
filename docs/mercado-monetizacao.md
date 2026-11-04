# Mercado, monetização e landing page — Mandalore

Data: 16/10/2026. Preços de concorrentes coletados nos sites públicos nesta data. Valores de custo são estimativas e devem ser confirmados no primeiro piloto.

## 1. Resumo

- O mercado de WhatsApp com agendamento para saúde está cheio no segmento de clínicas pequenas (R$ 200 a R$ 1.000 por mês) e pouco transparente no segmento hospitalar com Tasy, onde todos vendem "sob consulta".
- O Mandalore não deve competir com as ferramentas de clínica. O posicionamento viável é **agendamento por WhatsApp que grava direto no Tasy, implantado por quem conhece o Tasy**, vendido para hospitais e centros de diagnóstico que já usam Tasy.
- Dá para publicar uma landing page agora, desde que ela venda o **programa piloto** e mostre só o que funciona: marcar consulta e exame, com orientação de preparo.
- O melhor modelo de cobrança é **setup + mensalidade por faixa de agendamentos**, com piloto pago de 90 dias e conversão com abatimento.

## 2. Mercado

### 2.1 Concorrentes que anunciam integração com Tasy

| Empresa | O que anuncia | Preço público | Observação |
|---|---|---|---|
| [Cloudia](https://cloudia.com.br/integracao/tasy/) | Secretária virtual, agendamento, lembretes, NPS, multicanal | Sob consulta | Integração Tasy "em até 90 dias" |
| [SVChat Saúde](https://www.svchat.com.br/verticais/saude.html) | Agendamento, confirmação, triagem, convênio/TISS, secretárias humanas | Sob consulta | Conector com MV, Tasy, Doctoralia |
| [Maxbot](https://www.maxbot.com.br/whatsapp-para-clinicas-e-hospitais) | Multiatendente, chatbot com IA, agendamento 24h, lembrete | Sob consulta | Integração Tasy via "API aberta" |
| [Botdesigner](https://botdesigner.io/como-integrar-o-philips-tasy-ao-whatsapp-para-agendamento-automatizado/) | Agendamento com consulta à agenda em tempo real | Sob consulta | Conteúdo focado em Tasy |
| [wChat](https://wchat.com.br/clinicas/) | Chatbot, IA, integração com sistemas no plano Enterprise | R$ 597 e R$ 997/mês; Enterprise sob consulta | Integração com Tasy só no Enterprise |

### 2.2 Ferramentas de clínica (referência de preço, não concorrente direto)

| Ferramenta | Preço público |
|---|---|
| [We Ramp](https://weramp.com.br/precos) | R$ 297 ou R$ 497/mês + R$ 997 de implantação |
| [Atendize](https://atendize.com/melhor-sistema-whatsapp-clinica.html) | R$ 197 a R$ 997/mês |
| [Huggy](https://www.huggy.io/pt-br/pricing) | A partir de R$ 729/mês + mensagens WhatsApp |
| Zenvia | R$ 600/mês + pacote de canal |
| Doctoralia Pro | R$ 429 a R$ 529/mês |

Essas ferramentas não gravam no Tasy. Servem para mostrar que o hospital vai comparar a mensalidade com algo entre R$ 500 e R$ 1.500. O preço do Mandalore precisa ser justificado pela integração e pelo resultado, não pelo chat.

### 2.3 Onde o Mandalore se diferencia hoje

| Diferencial | Situação no código |
|---|---|
| Lê a agenda real do Tasy por JDBC, sem planilha nem cópia | Pronto |
| Grava no Tasy com proteção contra dupla reserva e repetição de mensagem | Pronto para consulta; exame pronto na lógica, depende do modo de gravação no hospital |
| Exame com orientação de preparo do próprio Tasy | Pronto |
| Não instala nada dentro do Tasy e não cria cadastro | Pronto |
| Trilha operacional sem CPF, telefone nem conteúdo de mensagem (LGPD) | Pronto |
| Equipe que já integra Tasy (Protheus, agendamento-ds, isaude) | Ativo comercial da Digisystem |

### 2.4 Onde perde hoje

- Sem lembrete e confirmação ativa. É o argumento número um do mercado ("reduza faltas em até 70%").
- Sem atendente humano no mesmo número (todos os concorrentes têm multiatendente).
- Sem listar, cancelar e remarcar no hospital.
- Só texto, sem botões, áudio ou IA generativa.

O lembrete com confirmação é a próxima funcionalidade com maior impacto em venda. Ele usa template de utilidade (R$ 0,035 por mensagem) e alimenta o indicador de faltas.

## 3. Argumento de valor (ROI)

Faltas sem aviso em clínicas e hospitais privados ficam entre 5% e 20% para 85% das instituições, e 32% delas passam de 11% ([Panorama das Clínicas e Hospitais 2025](https://realtime1.com.br/wp-content/uploads/2025/03/Panorama-das-Clinicas-e-Hospitais-2025.pdf)). No SUS especializado, estudos medem 38,6% em consultas e 32,1% em exames ([Beltrame et al.](https://doi.org/10.1590/0103-1104201912303)).

Exemplo para levar à reunião (substituir pelos números do hospital):

| Premissa | Valor |
|---|---|
| Agendamentos por mês pelo telefone e WhatsApp manual | 4.000 |
| Parcela que migra para o Mandalore | 30% (1.200) |
| Tempo médio da recepção por agendamento | 4 minutos |
| Horas de recepção liberadas por mês | 80 horas |
| Custo de uma atendente com encargos | R$ 4.500 por mês (~170 horas) |
| Economia de recepção | ~R$ 2.100 por mês |
| Agendamentos fora do horário comercial (capturados que seriam perdidos) | 20% de 1.200 = 240 |
| Ticket médio de consulta ou exame | R$ 200 |
| Receita potencial adicional | até R$ 48.000 por mês |

A economia de recepção sozinha paga uma mensalidade baixa. O argumento que fecha contrato é a receita de agendamento fora do horário e, quando existir o lembrete, a redução de faltas.

## 4. Custo variável por agendamento (pago pelo hospital)

Estimativa para uma marcação de ~12 mensagens do bot e ~12 requisições ao Dialogflow, câmbio R$ 5,50:

| Item | Base | Custo estimado |
|---|---|---|
| Meta, mensagem de serviço | R$ 0,035 após 1.000 grátis/mês por número | até R$ 0,42 |
| Twilio, taxa por mensagem | ~US$ 0,005 por mensagem | ~R$ 0,33 |
| Dialogflow ES, texto | ~US$ 0,002 por requisição | ~R$ 0,13 |
| **Total** | | **~R$ 0,90 por agendamento** |

Para 1.200 marcações por mês, são cerca de R$ 1.100 de terceiros. Isso precisa aparecer na proposta para não virar surpresa. Reduzir mensagens por fluxo (menos turnos, listas mais curtas) baixa esse custo diretamente.

Alternativa futura: trocar Twilio por um BSP brasileiro ou pela Cloud API direta da Meta elimina a taxa da Twilio.

## 5. Modelos de monetização

| Modelo | Como funciona | Prós | Contras |
|---|---|---|---|
| Licença fixa por unidade | Mensalidade única por hospital | Simples, previsível | Hospital grande paga igual ao pequeno |
| Por agendamento concluído | R$ X por marcação gravada no Tasy | Alinha valor e preço; fácil de vender no piloto | Receita instável; hospital pode limitar uso |
| **Híbrido (recomendado)** | Setup + mensalidade com franquia de agendamentos + excedente | Previsível e escala com o uso | Exige medir marcações (já possível pelo `NM_USUARIO`) |
| Serviço gerenciado | Digisystem opera o servidor e a conta | Margem maior, menos fricção de TI | Responsabilidade de infraestrutura e LGPD maior |

### 5.1 Planos propostos

Valores de referência para discussão interna. Ajustar após o primeiro piloto.

| Plano | Para quem | Inclui | Setup | Mensalidade | Franquia | Excedente |
|---|---|---|---|---|---|---|
| **Piloto** | Primeiro contato, 90 dias | Consulta e exame com preparo, 1 número, relatórios quinzenais | R$ 8.000 | R$ 2.500 | 1.500 marcações | sem cobrança |
| **Essencial** | Hospital ou CDI de porte médio | Piloto + sustentação em horário comercial | R$ 25.000 | R$ 4.500 | 2.000 marcações | R$ 1,50 |
| **Gestão** | Hospital com alto volume | Essencial + listar, confirmar, cancelar, remarcar e paciente sem cadastro | R$ 35.000 | R$ 7.500 | 5.000 marcações | R$ 1,20 |
| **Completo** | Rede ou hospital grande | Gestão + lembrete com confirmação ativa, transbordo humano, SLA 24x7 | sob consulta | a partir de R$ 12.000 | 10.000 marcações | R$ 1,00 |

Regras:

- Custos de Meta, Twilio, Google e infraestrutura sempre por conta do hospital (ou repassados com taxa de gestão no modelo gerenciado).
- Unidade ou número de WhatsApp adicional: +40% da mensalidade.
- Contrato definitivo de 12 meses, reajuste IPCA, como no `contrato-mandalore.md`.
- Setup do piloto abatido do setup definitivo na conversão.

Só vender "Gestão" e "Completo" quando as funcionalidades estiverem implementadas e homologadas.

## 6. Landing page

### 6.1 Dá para ter agora?

Sim, com três condições:

1. Vender **o piloto**, com chamada "Agende uma demonstração" ou "Candidate seu hospital ao piloto", e não um produto pronto em prateleira.
2. Mostrar só o que funciona: marcar consulta e exame, orientação de preparo, gravação direta no Tasy, LGPD. Lembrete, cancelamento, remarcação e atendimento humano aparecem como "em breve" ou não aparecem.
3. Não publicar números de resultado que ainda não temos (como "reduza 70% das faltas"). Usar dados de mercado com fonte, e trocar por dados do piloto depois.

### 6.2 Ponto de atenção: nome

"Mandalore" é o codinome usado só na demonstração para o hospital. Por ser um nome do universo Star Wars (marca da Lucasfilm/Disney), não aparece na landing page, no contrato definitivo nem no material comercial. O nome comercial precisa de busca no INPI (classes 9, 42 e 44) e de domínio `.com.br` disponível antes da publicação.

### 6.3 Estrutura sugerida

1. **Hero:** "Seu paciente marca consulta e exame pelo WhatsApp. A agenda do Tasy atualiza sozinha." Botão: "Quero participar do piloto".
2. **Problema:** recepção sobrecarregada, telefone ocupado, agendamento perdido fora do horário, faltas (com fonte).
3. **Como funciona:** 4 passos com prints reais do simulador ou do WhatsApp do Sandbox — CPF e nascimento, convênio, horário, confirmação com preparo do exame.
4. **Feito para Tasy:** lê e grava na agenda real, sem planilha, sem dupla reserva, sem instalar nada no Tasy.
5. **Segurança e LGPD:** API oficial do WhatsApp, criptografia com o Oracle, logs sem CPF e sem conteúdo de mensagem, hospital como controlador.
6. **Programa piloto:** 90 dias, implantação assistida, relatório de indicadores, abatimento do setup na contratação.
7. **Roadmap:** lembrete com confirmação, cancelamento e remarcação, atendimento humano no mesmo número.
8. **Quem está por trás:** Digisystem, experiência em integração com Tasy.
9. **Formulário:** nome, hospital, cargo, e-mail, telefone, usa Tasy (sim/não), volume mensal de agendamentos.
10. **FAQ:** custo do WhatsApp, prazo da Meta, o que o hospital precisa prover, modo de gravação no Tasy.

### 6.4 Como montar

| Opção | Prazo | Custo | Quando usar |
|---|---|---|---|
| Página no site da Digisystem | 1 a 2 semanas | Equipe de marketing interna | Recomendado: herda credibilidade e domínio |
| Domínio próprio com construtor (Framer, Webflow, Wix) | 2 a 5 dias | ~R$ 100 a R$ 300/mês | Se quiser marca separada |
| Página estática (Astro ou Next.js) em Cloudflare Pages ou Vercel | 3 a 5 dias de dev | Praticamente zero | Se o time de dev mantiver |

Itens obrigatórios: política de privacidade e aviso LGPD no formulário, envio dos leads para o CRM comercial, vídeo curto (60 a 90 segundos) gravado no simulador mostrando consulta e exame.

### 6.5 Canais para levar tráfego

- Lista de hospitais usuários de Tasy da carteira Digisystem (melhor canal: venda consultiva direta).
- LinkedIn para gestores de TI, diretoria de atendimento e superintendentes de hospitais.
- Eventos de saúde (Hospitalar, eventos de usuários Tasy).
- Caso de uso do primeiro piloto, com autorização (cláusula 3, "i", do contrato de betateste).

## 7. Cenário recomendado

1. **Agora:** escolher o nome comercial, publicar a landing page do piloto no site da Digisystem e gravar o vídeo no simulador.
2. **Em paralelo:** ofertar o piloto a 2 ou 3 hospitais Tasy da carteira, no plano Piloto, com o `contrato-betateste-mandalore.md`.
3. **Antes do primeiro go-live:** definir com o hospital o modo de gravação de exame (agendamento-ds com `CD_INTEGRACAO` ou `UPDATE` condicionado) e esconder o item "ver, remarcar ou cancelar" do menu do agente.
4. **Durante o piloto:** implementar lembrete com confirmação e o pacote de gestão (listar, confirmar, cancelar, remarcar) na integração real do hospital.
5. **Ao fim do piloto:** converter para Essencial ou Gestão, publicar o caso com números reais e reajustar os preços da tabela.
