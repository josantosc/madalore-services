# Orçamento por foto do pedido

O paciente manda a foto (ou PDF) do pedido médico no chat. O Mandalore:

1. Responde na hora: "Recebi o seu pedido. Vou conferir os exames e um atendente te passa o orçamento por aqui em instantes."
2. Baixa o anexo do Chatwoot (até 10 MB) e pede ao **Gemini** (Vertex AI) a lista de exames, casando siglas
   (HMG, TGO, EAS...) com os nomes da tabela de preços.
3. Deixa uma **nota privada** na conversa com o rascunho: texto pronto para o paciente, valor por exame,
   prazo do resultado, total e os exames sem preço na tabela.
4. Atribui a equipe **Faturamento/Convênios** (`CHATWOOT_EQUIPE_FATURAMENTO`) e abre a conversa.

O bot **nunca envia valores ao paciente**. O atendente confere a nota com a foto, ajusta e envia.
Imagem que não é pedido médico vai para a Recepção com uma nota avisando. Se a leitura falhar,
a conversa vai para o Faturamento com a nota "Não consegui ler a foto do pedido automaticamente".

Funciona nas conversas que estão com o bot (status Pendente), em qualquer canal do Chatwoot.

## Configuração

### Google Cloud

No projeto do Dialogflow (o mesmo do `DIALOGFLOW_CREDENTIALS`):

1. Ative a API **Vertex AI** (`aiplatform.googleapis.com`).
2. No IAM, dê à conta de serviço o papel **Vertex AI User**.

O Vertex AI não usa as imagens para treinar modelos. Custo: centavos de real por foto com o
`gemini-3.8-flash`.

### Coolify (Mandalore)

```
ORCAMENTO_ENABLED=true
CHATWOOT_EQUIPE_FATURAMENTO=<id da equipe>
```

Opcionais:

| Variável | Padrão | Uso |
|---|---|---|
| `GEMINI_PROJECT_ID` | `DIALOGFLOW_PROJECT_ID` | Outro projeto Google |
| `GEMINI_LOCATION` | `global` | `southamerica-east1` mantém o processamento no Brasil, se o modelo estiver disponível lá |
| `GEMINI_MODEL` | `gemini-3.8-flash` | Outro modelo Gemini |

Requer `OBSERVABILITY_POSTGRES_ENABLED=true` e `OBSERVABILITY_POSTGRES_INITIALIZE_SCHEMA=true`
(cria `orcamento.item_preco`). Para importar a tabela pela API, também `CURADORIA_ENABLED=true`.

## Tabela de preços

Planilha com uma linha por exame, salva como **CSV** (no Excel: *Salvar como → CSV (separado por vírgulas)*;
o Excel em português grava com `;`, que é aceito):

| Coluna | Obrigatória | Exemplo |
|---|---|---|
| `codigo` | sim | `HMG` |
| `nome` | sim | `Hemograma completo` |
| `sinonimos` | não | `HMG\|Hemograma\|Hemograma com plaquetas` (separados por `\|`) |
| `valor` | sim | `25,00` ou `R$ 1.250,00` |
| `prazo` | não | `1 dia útil` |

Modelo com 28 exames comuns e valores zerados: [`deploy/tabela-precos.exemplo.csv`](../deploy/tabela-precos.exemplo.csv).
Preencha os valores antes de importar.

Importar (substitui a tabela inteira; arquivo com erro não altera nada):

```bash
curl -u curadoria -X PUT --data-binary @tabela-precos.csv \
  -H "Content-Type: text/csv" https://bs-api.jscinfor.com/curadoria/api/tabela-precos
# {"itens":28}
```

O `curl -u curadoria` pede a senha (`CURADORIA_PASSWORD`) sem deixá-la no histórico do terminal.
Erro de linha volta como `{"erro":"Linha 5: valor inválido \"doze\""}`.

Conferir: `GET /curadoria/api/tabela-precos` (mesmo usuário e senha).

Sinônimos melhoram o casamento quando o Gemini não reconhece um nome. Exame que aparece em
"Sem preço na tabela" com frequência deve entrar na tabela ou ganhar sinônimo.

## Nota privada (exemplo)

```
Orçamento sugerido pela leitura automática do pedido. Confira com a foto antes de enviar.

--- Texto para o paciente ---
Orçamento dos exames do seu pedido (particular):
- Hemograma completo: R$ 25,00 (resultado em 1 dia útil)
- Glicose: R$ 12,00 (resultado em 1 dia útil)
Total: R$ 37,00
Valores para pagamento particular. Se for usar convênio, me diga qual é.
---

Sem preço na tabela (verificar):
- Anti-TPO
```

## LGPD

A foto do pedido é dado de saúde. O Gemini recebe só a imagem e a lista de nomes da tabela, com a
instrução de não transcrever nome, documento, médico ou CID. O Mandalore não grava a imagem nem
os exames lidos; o log registra só quantidades (`orcamento_pedido exames=3 sem_preco=1`).
A imagem continua no Chatwoot, como qualquer anexo.
