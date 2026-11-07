"""Publica o agente Dialogflow ES de agendamento de consulta.

Credencial: arquivo da conta de serviço (nao vai para o repositorio).
Uso: py scripts/publicar_dialogflow.py
"""

import argparse
import base64
import json
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

CREDENTIALS = Path(r"J:\arquivos\credenciais-bot-demostracao.json")
ENV_FILE = Path(__file__).resolve().parents[1] / ".env"
MANIFEST_FILE = (
    Path(__file__).resolve().parents[1]
    / "src"
    / "test"
    / "resources"
    / "dialogflow"
    / "agent-manifest.json"
)
PROJECT_FALLBACK = "demonstracao-hkgc"
MANIFEST_PROJECT = "regression-project"
FALHA = "Não consegui continuar agora. Pode tentar de novo ou pedir um atendente."
API = "https://dialogflow.googleapis.com/v2"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def access_token(sa: dict) -> str:
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claim = b64(json.dumps({
        "iss": sa["client_email"],
        "scope": "https://www.googleapis.com/auth/cloud-platform",
        "aud": "https://oauth2.googleapis.com/token",
        "iat": now,
        "exp": now + 3600,
    }).encode())
    signing = f"{header}.{claim}".encode()
    key = serialization.load_pem_private_key(sa["private_key"].encode(), password=None)
    signature = key.sign(signing, padding.PKCS1v15(), hashes.SHA256())
    assertion = signing.decode() + "." + b64(signature)
    body = urllib.parse.urlencode({
        "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
        "assertion": assertion,
    }).encode()
    request = urllib.request.Request("https://oauth2.googleapis.com/token", data=body)
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)["access_token"]


def call(method: str, url: str, token: str, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    if data is not None:
        request.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"{method} {url} -> {error.code}\n{detail}")


def wait(operation: dict, token: str, timeout_seconds: int = 180) -> dict:
    limite = time.monotonic() + timeout_seconds
    while not operation.get("done"):
        if time.monotonic() >= limite:
            raise TimeoutError(
                f"Operação {operation.get('name', '<sem nome>')} não concluiu em {timeout_seconds}s"
            )
        time.sleep(2)
        _, operation = call("GET", f"{API}/{operation['name']}", token)
    if "error" in operation:
        raise SystemExit(json.dumps(operation["error"], ensure_ascii=False))
    return operation


def list_all(url: str, token: str, campo: str) -> list:
    itens = []
    page = ""
    while True:
        separador = "&" if "?" in url else "?"
        alvo = url if not page else f"{url}{separador}pageToken={urllib.parse.quote(page)}"
        _, payload = call("GET", alvo, token)
        itens.extend(payload.get(campo, []))
        page = payload.get("nextPageToken") or ""
        if not page:
            return itens


def ctx(project: str, nome: str) -> str:
    return f"projects/{project}/agent/sessions/-/contexts/{nome}"


def saida(project: str, nome: str, vida: int) -> dict:
    return {"name": ctx(project, nome), "lifespanCount": vida}


def texto(mensagem: str) -> dict:
    return {"text": {"text": [mensagem]}}


def exemplo(*partes) -> dict:
    montadas = []
    for parte in partes:
        if isinstance(parte, str):
            montadas.append({"text": parte})
        else:
            valor, entidade, apelido = parte
            montadas.append({
                "text": valor,
                "entityType": entidade,
                "alias": apelido,
                "userDefined": True,
            })
    return {"type": "EXAMPLE", "parts": montadas}


def parametro(nome, entidade, obrigatorio=False, prompts=None, padrao=None) -> dict:
    corpo = {
        "displayName": nome,
        "entityTypeDisplayName": entidade,
        "value": f"${nome}",
        "mandatory": obrigatorio,
        "isList": False,
    }
    if prompts:
        corpo["prompts"] = prompts
    if padrao:
        corpo["defaultValue"] = padrao
    return corpo


def intent(nome, mensagens, **extra) -> dict:
    corpo = {
        "displayName": nome,
        "priority": extra.pop("priority", 500000),
        "webhookState": extra.pop("webhookState", "WEBHOOK_STATE_UNSPECIFIED"),
        "isFallback": extra.pop("isFallback", False),
        "mlDisabled": extra.pop("mlDisabled", False),
        "events": extra.pop("events", []),
        "trainingPhrases": extra.pop("trainingPhrases", []),
        "inputContextNames": extra.pop("inputContextNames", []),
        "outputContexts": extra.pop("outputContexts", []),
        "parameters": extra.pop("parameters", []),
        "messages": [texto(item) for item in mensagens],
        "resetContexts": False,
    }
    corpo.update(extra)
    return corpo


def entidades() -> list:
    return [
        {
            "displayName": "cpf",
            "kind": "KIND_REGEXP",
            "entities": [{
                "value": r"\d{3}\.?\d{3}\.?\d{3}-?\d{2}",
                "synonyms": [r"\d{3}\.?\d{3}\.?\d{3}-?\d{2}"],
            }],
        },
        {
            "displayName": "periodo",
            "kind": "KIND_MAP",
            "entities": [
                {"value": "manha", "synonyms": ["manha", "manhã", "de manhã", "cedo"]},
                {"value": "tarde", "synonyms": ["tarde", "à tarde", "a tarde", "de tarde"]},
                {"value": "tanto_faz", "synonyms": ["tanto faz", "qualquer", "indiferente"]},
            ],
        },
        {
            "displayName": "confirmacao",
            "kind": "KIND_MAP",
            "entities": [
                {"value": "sim", "synonyms": ["sim", "isso", "pode", "pode marcar", "confirmo", "quero"]},
                {"value": "nao", "synonyms": ["nao", "não", "não quero", "nao quero", "outro horário", "outro horario", "troca"]},
            ],
        },
        {
            "displayName": "navegacao",
            "kind": "KIND_MAP",
            "entities": [
                {"value": "voltar", "synonyms": ["voltar", "volta", "passo anterior"]},
            ],
        },
    ]


def numero(frase: str, nome: str = "escolha") -> dict:
    partes = []
    resto = frase
    while resto:
        indice = next((i for i, char in enumerate(resto) if char.isdigit()), None)
        if indice is None:
            partes.append(resto)
            break
        if indice:
            partes.append(resto[:indice])
        fim = indice
        while fim < len(resto) and resto[fim].isdigit():
            fim += 1
        partes.append((resto[indice:fim], "@sys.number", nome))
        resto = resto[fim:]
    return exemplo(*partes)


def frases_numero(prefixo: str) -> list:
    frases = []
    for n in range(1, 9):
        frases.append(numero(f"{prefixo} {n}"))
        frases.append(numero(f"{prefixo} o {n}"))
        frases.append(numero(f"{prefixo} a {n}"))
    return frases


def montar_intents(project: str) -> list:
    paciente = [ctx(project, "paciente")]
    paciente_convenio = [ctx(project, "paciente"), ctx(project, "convenio")]
    paciente_convenio_busca = paciente_convenio + [ctx(project, "tipo_busca")]
    paciente_convenio_esp = paciente_convenio + [ctx(project, "especialidade")]
    webhook = "WEBHOOK_STATE_ENABLED"
    paciente_convenio_sem_horario_exame = paciente_convenio + [ctx(project, "sem_horario_exame")]
    saudacao = (
        "Olá! Como posso ajudar?\n\n"
        "1. Marcar consulta\n"
        "2. Marcar exame\n"
        "3. Ver, remarcar ou cancelar um agendamento\n"
        "4. Orçamento de exames\n\n"
        "Responda com o número ou descreva o que você precisa."
    )
    orcamento = (
        "Mande aqui uma foto do pedido médico ou, se não tiver o pedido, escreva os nomes dos exames "
        "(por exemplo: hemograma, glicose e TSH). A equipe do Faturamento te passa o orçamento."
    )
    frases_orcamento = [
        exemplo("4"),
        exemplo("opção 4"),
        exemplo("opcao 4"),
        exemplo("orçamento"),
        exemplo("orcamento"),
        exemplo("orçamento de exames"),
        exemplo("quero um orçamento"),
        exemplo("fazer orçamento"),
    ]
    periodo = (
        "Qual período fica melhor?\n\n"
        "1. Manhã\n"
        "2. Tarde\n"
        "3. Qualquer período\n\n"
        "Responda com o número ou diga manhã, tarde ou qualquer período."
    )
    dia = "Para qual dia? Pode ser uma data, como 20/07/2026, ou esta semana."
    encerramento = (
        "Atendimento encerrado. Foi um prazer ajudar!\n\n"
        "Quando precisar, é só enviar uma nova mensagem."
    )
    return [
        intent(
            "texto.saudacao",
            [saudacao],
            priority=250000,
            events=["WELCOME"],
            outputContexts=[saida(project, "menu", 3)],
            trainingPhrases=[
                exemplo("oi"),
                exemplo("olá"),
                exemplo("ola"),
                exemplo("bom dia"),
                exemplo("boa tarde"),
                exemplo("boa noite"),
                exemplo("começar"),
                exemplo("menu"),
            ],
        ),
        intent(
            "texto.saudacao.retomar",
            [
                "Olá novamente, $nome! O que você gostaria de fazer agora?\n\n"
                "1. Marcar uma consulta\n"
                "2. Marcar um exame\n"
                "3. Ver, remarcar ou cancelar um agendamento\n"
                "4. Orçamento de exames"
            ],
            priority=940000,
            inputContextNames=[ctx(project, "paciente")],
            outputContexts=[
                saida(project, "apos_identificar", 4),
                saida(project, "lista", 0),
                saida(project, "menu", 0),
                saida(project, "exame", 0),
            ],
            parameters=[parametro("nome", "@sys.any", padrao="#paciente.nome")],
            trainingPhrases=[
                exemplo("oi"),
                exemplo("olá"),
                exemplo("ola"),
                exemplo("bom dia"),
                exemplo("boa tarde"),
                exemplo("boa noite"),
                exemplo("começar"),
                exemplo("menu"),
            ],
        ),
        intent(
            "texto.fallback",
            ["Não entendi. Pode reformular ou pedir um atendente?"],
            isFallback=True,
            priority=500000,
        ),
        intent(
            "texto.conversa.reiniciar",
            [
                "Pronto, vamos começar uma nova conversa.\n\n"
                "1. Marcar consulta\n"
                "2. Marcar exame\n"
                "3. Ver, remarcar ou cancelar um agendamento\n"
                "4. Orçamento de exames\n\n"
                "Como posso ajudar?"
            ],
            priority=1000000,
            resetContexts=True,
            outputContexts=[saida(project, "menu", 3)],
            trainingPhrases=[
                exemplo("reiniciar"),
                exemplo("recomeçar"),
                exemplo("recomecar"),
                exemplo("começar de novo"),
                exemplo("comecar de novo"),
                exemplo("nova conversa"),
                exemplo("iniciar novamente"),
                exemplo("limpar conversa"),
            ],
        ),
        intent(
            "texto.coleta.nascimento",
            ["Para achar o cadastro, qual o CPF de quem será atendido?"],
            priority=600000,
            inputContextNames=[ctx(project, "menu")],
            outputContexts=[saida(project, "exame", 0)],
            trainingPhrases=[
                exemplo("1"),
                exemplo("3"),
                exemplo("marcar consulta"),
                exemplo("quero marcar uma consulta"),
                exemplo("ver, remarcar ou cancelar"),
                exemplo("ver, remarcar e cancelar"),
            ],
        ),
        intent(
            "texto.menu.orcamento",
            [orcamento],
            priority=600000,
            inputContextNames=[ctx(project, "menu")],
            outputContexts=[saida(project, "menu", 0)],
            trainingPhrases=frases_orcamento,
        ),
        intent(
            "texto.menu.orcamento.retomar",
            [orcamento],
            priority=660000,
            inputContextNames=paciente + [ctx(project, "apos_identificar")],
            trainingPhrases=frases_orcamento,
        ),
        intent(
            "texto.exame.iniciar",
            ["Para achar o cadastro, qual o CPF de quem será atendido?"],
            priority=600000,
            inputContextNames=[ctx(project, "menu")],
            outputContexts=[saida(project, "exame", 20)],
            trainingPhrases=[
                exemplo("2"),
                exemplo("exame"),
                exemplo("marcar exame"),
                exemplo("marcar um exame"),
                exemplo("quero marcar um exame"),
                exemplo("agendar exame"),
            ],
        ),
        intent(
            "texto.coleta.cpf",
            ["Obrigado. Qual o CPF de quem vai ser atendido?"],
            priority=200000,
            mlDisabled=True,
            trainingPhrases=[
                exemplo("não sei o cpf"),
                exemplo("esqueci o cpf"),
                exemplo("qual cpf"),
            ],
        ),
        intent(
            "texto.coleta.periodo",
            [periodo],
            priority=300000,
            mlDisabled=True,
            events=["RESULTADO_PERIODO"],
            outputContexts=[saida(project, "pedindo_horario", 4)],
        ),
        intent(
            "texto.coleta.dia",
            [dia],
            priority=300000,
            mlDisabled=True,
            events=["RESULTADO_DIA"],
            inputContextNames=[ctx(project, "periodo")],
            outputContexts=[saida(project, "pedindo_horario", 4)],
        ),
        intent(
            "texto.consulta.marcar_outro",
            ["Certo. Diga continuar para eu ver os convênios."],
            priority=400000,
            inputContextNames=[ctx(project, "agendamento_futuro")],
            trainingPhrases=[
                exemplo("marcar outro"),
                exemplo("quero outra consulta"),
                exemplo("outra consulta"),
            ],
        ),
        intent(
            "texto.consulta.seguir_especialidade",
            [periodo],
            priority=700000,
            inputContextNames=[ctx(project, "confirma_especialidade")],
            outputContexts=[
                saida(project, "pedindo_horario", 4),
                saida(project, "confirma_especialidade", 0),
            ],
            parameters=[parametro("confirmacao", "@confirmacao")],
            trainingPhrases=[
                exemplo(("sim", "@confirmacao", "confirmacao")),
                exemplo(("pode", "@confirmacao", "confirmacao")),
                exemplo("seguir"),
                exemplo("sigo"),
                exemplo("quero essa"),
            ],
        ),
        intent(
            "texto.navegacao.voltar",
            ["Certo, voltei um passo. Pode escolher de novo ou dizer o que quer fazer."],
            priority=1000000,
            parameters=[parametro("navegacao", "@navegacao")],
            outputContexts=[
                saida(project, "lista", 0),
                saida(project, "oferta", 0),
                saida(project, "aguardando_confirmacao", 0),
                saida(project, "dia", 0),
            ],
            trainingPhrases=[
                exemplo(("voltar", "@navegacao", "navegacao")),
                exemplo(("volta", "@navegacao", "navegacao")),
                exemplo("passo anterior"),
            ],
        ),
        intent(
            "transbordo.atendente",
            ["Vou chamar alguém da equipe."],
            priority=950000,
            trainingPhrases=[
                exemplo("atendente"),
                exemplo("quero falar com alguém"),
                exemplo("falar com uma pessoa"),
                exemplo("quero um humano"),
                exemplo("operador"),
                exemplo("quero falar com atendente"),
                exemplo("quero falar com um atendente"),
                exemplo("falar com atendente"),
                exemplo("me passa para um atendente"),
                exemplo("atendimento humano"),
                exemplo("quero falar com uma pessoa de verdade"),
            ],
        ),
        intent(
            "texto.sem_horario.outro_periodo",
            [periodo],
            priority=980000,
            inputContextNames=[ctx(project, "sem_horario")],
            outputContexts=[
                saida(project, "sem_horario", 0),
                saida(project, "pedindo_horario", 4),
                saida(project, "periodo", 0),
                saida(project, "dia", 0),
                saida(project, "oferta", 0),
            ],
            trainingPhrases=[
                exemplo("1"),
                exemplo("outro período"),
                exemplo("outro periodo"),
                exemplo("tentar outro período"),
                exemplo("ver outros horários"),
            ],
        ),
        intent(
            "texto.sem_horario.nova_busca",
            [
                "Tudo bem. Como você prefere fazer uma nova busca?\n\n"
                "1. Pela clínica ou especialidade\n"
                "2. Por um médico de preferência"
            ],
            priority=980000,
            inputContextNames=[ctx(project, "sem_horario")],
            outputContexts=[
                saida(project, "sem_horario", 0),
                saida(project, "tipo_busca", 3),
                saida(project, "especialidade", 0),
                saida(project, "profissional", 0),
                saida(project, "periodo", 0),
                saida(project, "dia", 0),
                saida(project, "lista", 0),
                saida(project, "oferta", 0),
            ],
            trainingPhrases=[
                exemplo("2"),
                exemplo("outra especialidade"),
                exemplo("outro médico"),
                exemplo("outro medico"),
                exemplo("fazer outra busca"),
            ],
        ),
        intent(
            "transbordo.sem_horario.atendente",
            ["Entendi. Vou encaminhar você para alguém da equipe continuar o atendimento."],
            priority=990000,
            inputContextNames=[ctx(project, "sem_horario")],
            outputContexts=[saida(project, "sem_horario", 0)],
            trainingPhrases=[
                exemplo("3"),
                exemplo("falar com atendente"),
                exemplo("quero ajuda"),
            ],
        ),
        intent(
            "texto.sem_horario.fallback",
            ["Escolha uma opção: 1 para outro período, 2 para uma nova busca ou 3 para falar com a equipe."],
            priority=970000,
            isFallback=True,
            inputContextNames=[ctx(project, "sem_horario")],
        ),
        intent(
            "acao.paciente.identificar",
            [FALHA],
            priority=750000,
            webhookState=webhook,
            inputContextNames=[ctx(project, "menu")],
            outputContexts=[saida(project, "identificando", 5)],
            parameters=[
                parametro(
                    "cpf",
                    "@cpf",
                    obrigatorio=True,
                    prompts=[
                        "Qual o CPF de quem será atendido?",
                        "Confere o CPF? Preciso dos 11 números.",
                    ],
                    padrao="#identificando.cpf",
                ),
                parametro(
                    "data_nascimento",
                    "@sys.date",
                    obrigatorio=True,
                    prompts=[
                        "Para achar o cadastro, qual a data de nascimento? Pode ser dia/mês/ano, por exemplo 19/10/1988.",
                        "Agora informe a data de nascimento, com dia, mês e ano. Por exemplo: 19/10/1988.",
                    ],
                ),
            ],
            trainingPhrases=[
                exemplo(("19/10/1988", "@sys.date", "data_nascimento")),
                exemplo(("10/05/1990", "@sys.date", "data_nascimento")),
                exemplo("nasci em ", ("01/02/1980", "@sys.date", "data_nascimento")),
                exemplo("cpf ", ("529.982.247-25", "@cpf", "cpf")),
                exemplo(("52998224725", "@cpf", "cpf")),
                exemplo(
                    ("19/10/1988", "@sys.date", "data_nascimento"),
                    " cpf ",
                    ("529.982.247-25", "@cpf", "cpf"),
                ),
                exemplo(
                    "nascimento ",
                    ("10/05/1990", "@sys.date", "data_nascimento"),
                    " e documento ",
                    ("111.444.777-35", "@cpf", "cpf"),
                ),
            ],
        ),
        intent(
            "acao.paciente.sem_cadastro",
            [FALHA],
            priority=980000,
            webhookState=webhook,
            inputContextNames=[ctx(project, "opcao_sem_cadastro")],
            parameters=[
                parametro(
                    "nome",
                    "@sys.any",
                    obrigatorio=True,
                    prompts=[
                        "Para continuar, qual é o nome completo de quem será atendido?",
                        "Por favor, informe o nome completo de quem será atendido.",
                    ],
                ),
            ],
            trainingPhrases=[
                exemplo("1"),
                exemplo("sim"),
                exemplo("quero"),
                exemplo("pode agendar"),
                exemplo("agendar sem cadastro"),
                exemplo("continuar sem cadastro"),
            ],
        ),
        intent(
            "texto.paciente.tentar_novamente",
            ["Sem problema. Vamos conferir os dados novamente. Qual é o CPF de quem será atendido?"],
            priority=970000,
            inputContextNames=[ctx(project, "opcao_sem_cadastro")],
            outputContexts=[
                saida(project, "opcao_sem_cadastro", 0),
                saida(project, "paciente_sem_cadastro", 0),
                saida(project, "paciente", 0),
                saida(project, "identificando", 0),
                saida(project, "tem_nascimento", 0),
                saida(project, "menu", 3),
            ],
            trainingPhrases=[
                exemplo("2"),
                exemplo("não"),
                exemplo("nao"),
                exemplo("tentar novamente"),
                exemplo("tentar de novo"),
                exemplo("corrigir dados"),
                exemplo("informar outro cpf"),
            ],
        ),
        intent(
            "acao.consulta.convenios",
            [FALHA],
            priority=650000,
            webhookState=webhook,
            inputContextNames=paciente + [ctx(project, "apos_identificar")],
            trainingPhrases=[
                exemplo("continuar"),
                exemplo("pode seguir"),
                exemplo("quero marcar"),
                exemplo("marcar consulta"),
                exemplo("marcar outro"),
                exemplo("outra consulta"),
                exemplo("convênio"),
                exemplo("convenio"),
                exemplo("ver convênios"),
                exemplo("seguir"),
                exemplo("marcar"),
                exemplo("1"),
                exemplo("opção 1"),
                exemplo("opcao 1"),
            ],
        ),
        intent(
            "acao.exame.convenios",
            [FALHA],
            priority=660000,
            webhookState=webhook,
            inputContextNames=paciente + [ctx(project, "apos_identificar")],
            trainingPhrases=[
                exemplo("2"),
                exemplo("opção 2"),
                exemplo("opcao 2"),
                exemplo("exame"),
                exemplo("marcar exame"),
                exemplo("marcar um exame"),
                exemplo("quero marcar um exame"),
                exemplo("agendar exame"),
            ],
        ),
        intent(
            "acao.exame.exames",
            [FALHA],
            priority=980000,
            webhookState=webhook,
            inputContextNames=paciente_convenio_sem_horario_exame,
            outputContexts=[
                saida(project, "sem_horario_exame", 0),
                saida(project, "periodo", 0),
                saida(project, "dia", 0),
                saida(project, "oferta", 0),
            ],
            trainingPhrases=[
                exemplo("2"),
                exemplo("outro exame"),
                exemplo("escolher outro exame"),
                exemplo("trocar exame"),
            ],
        ),
        intent(
            "texto.sem_horario_exame.outro_periodo",
            [periodo],
            priority=980000,
            inputContextNames=[ctx(project, "sem_horario_exame")],
            outputContexts=[
                saida(project, "sem_horario_exame", 0),
                saida(project, "pedindo_horario", 4),
                saida(project, "periodo", 0),
                saida(project, "dia", 0),
                saida(project, "oferta", 0),
            ],
            trainingPhrases=[
                exemplo("1"),
                exemplo("outro período"),
                exemplo("outro periodo"),
                exemplo("tentar outro período"),
                exemplo("ver outros horários"),
            ],
        ),
        intent(
            "transbordo.sem_horario_exame.atendente",
            ["Entendi. Vou encaminhar você para alguém da equipe continuar o atendimento."],
            priority=990000,
            inputContextNames=[ctx(project, "sem_horario_exame")],
            outputContexts=[saida(project, "sem_horario_exame", 0)],
            trainingPhrases=[
                exemplo("3"),
                exemplo("falar com atendente"),
                exemplo("quero ajuda"),
            ],
        ),
        intent(
            "texto.sem_horario_exame.fallback",
            ["Escolha uma opção: 1 para outro período, 2 para outro exame ou 3 para falar com a equipe."],
            priority=970000,
            isFallback=True,
            inputContextNames=[ctx(project, "sem_horario_exame")],
        ),
        intent(
            "acao.consulta.novo.convenios",
            [FALHA],
            priority=980000,
            webhookState=webhook,
            inputContextNames=paciente + [ctx(project, "pos_agendamento")],
            trainingPhrases=[
                exemplo("1"),
                exemplo("marcar outra consulta"),
                exemplo("novo agendamento"),
                exemplo("agendar novamente"),
            ],
        ),
        intent(
            "texto.conversa.encerrar.pos_agendamento",
            [encerramento],
            priority=990000,
            resetContexts=True,
            inputContextNames=[ctx(project, "pos_agendamento")],
            trainingPhrases=[
                exemplo("2"),
                exemplo("encerrar"),
                exemplo("encerrar atendimento"),
                exemplo("não preciso de mais nada"),
                exemplo("nao preciso de mais nada"),
            ],
        ),
        intent(
            "texto.conversa.encerrar",
            [encerramento],
            priority=1000000,
            resetContexts=True,
            trainingPhrases=[
                exemplo("encerrar"),
                exemplo("encerrar atendimento"),
                exemplo("encerrar conversa"),
                exemplo("pode encerrar"),
                exemplo("finalizar"),
                exemplo("finalizar atendimento"),
                exemplo("sair"),
                exemplo("tchau"),
                exemplo("até logo"),
                exemplo("ate mais"),
                exemplo("era só isso"),
                exemplo("só isso"),
                exemplo("não preciso de mais nada"),
                exemplo("nao preciso de mais nada"),
                exemplo("não quero mais"),
                exemplo("fim"),
            ],
        ),
        intent(
            "texto.conversa.agradecimento",
            [
                "Por nada! Se precisar de mais alguma coisa, digite menu. "
                "Para encerrar o atendimento, digite encerrar."
            ],
            priority=500000,
            trainingPhrases=[
                exemplo("obrigado"),
                exemplo("obrigada"),
                exemplo("brigado"),
                exemplo("brigada"),
                exemplo("muito obrigado"),
                exemplo("muito obrigada"),
                exemplo("obg"),
                exemplo("valeu"),
                exemplo("agradeço"),
                exemplo("ok obrigado"),
                exemplo("obrigado pela ajuda"),
                exemplo("show, obrigado"),
            ],
        ),
        intent(
            "acao.consulta.profissionais",
            [FALHA],
            priority=920000,
            webhookState=webhook,
            inputContextNames=paciente_convenio_busca,
            trainingPhrases=[
                exemplo("2"),
                exemplo("por médico"),
                exemplo("por medico"),
                exemplo("médico de preferência"),
                exemplo("medico de preferencia"),
                exemplo("ver médicos"),
                exemplo("profissionais"),
                exemplo("quem já me atendeu"),
                exemplo("outro médico"),
            ],
        ),
        intent(
            "acao.consulta.especialidades",
            [FALHA],
            priority=920000,
            webhookState=webhook,
            inputContextNames=paciente_convenio_busca,
            parameters=[parametro("cd_medico", "@sys.any")],
            trainingPhrases=[
                exemplo("1"),
                exemplo("pela clínica"),
                exemplo("pela clinica"),
                exemplo("por especialidade"),
                exemplo("especialidades"),
                exemplo("outras especialidades"),
                exemplo("ver especialidades"),
            ],
        ),
        intent(
            "acao.consulta.escolher_lista",
            [FALHA],
            priority=900000,
            webhookState=webhook,
            inputContextNames=[ctx(project, "lista")],
            parameters=[parametro("escolha", "@sys.any")],
            trainingPhrases=[numero(str(n)) for n in range(1, 9)] + [
                exemplo("ver mais"),
                exemplo("ver outros"),
                exemplo("outra pessoa"),
                exemplo("outro"),
                {"type": "TEMPLATE", "parts": [{"text": "@sys.any:escolha"}]},
            ],
        ),
        intent(
            "acao.consulta.fallback.escolher_lista",
            [FALHA],
            priority=890000,
            webhookState=webhook,
            isFallback=True,
            inputContextNames=[ctx(project, "lista")],
            action="escolher_lista",
        ),
        intent(
            "acao.agenda.disponibilidade",
            [FALHA],
            priority=970000,
            webhookState=webhook,
            inputContextNames=[ctx(project, "pedindo_horario")],
            parameters=[
                parametro("periodo", "@periodo"),
                parametro("data", "@sys.date"),
            ],
            trainingPhrases=[
                exemplo(("manhã", "@periodo", "periodo")),
                exemplo(("tarde", "@periodo", "periodo")),
                exemplo(("tanto faz", "@periodo", "periodo")),
                exemplo("de ", ("manhã", "@periodo", "periodo")),
                exemplo("à ", ("tarde", "@periodo", "periodo")),
                exemplo(("20/07/2026", "@sys.date", "data")),
                exemplo(("amanhã", "@sys.date", "data")),
                exemplo("esta semana"),
                exemplo(("manhã", "@periodo", "periodo"), " ", ("amanhã", "@sys.date", "data")),
                exemplo("de ", ("tarde", "@periodo", "periodo"), " em ", ("20/07/2026", "@sys.date", "data")),
                exemplo("esta semana de ", ("manhã", "@periodo", "periodo")),
                exemplo(("1", "@sys.number", "escolha")),
                exemplo(("2", "@sys.number", "escolha")),
                exemplo(("3", "@sys.number", "escolha")),
            ],
        ),
        intent(
            "acao.agenda.meus_agendamentos",
            [FALHA],
            priority=720000,
            webhookState=webhook,
            inputContextNames=paciente + [ctx(project, "apos_identificar")],
            trainingPhrases=[
                exemplo("ver agendamentos"),
                exemplo("minhas consultas"),
                exemplo("meus agendamentos"),
                exemplo("ver consultas"),
                exemplo("ver esse agendamento"),
                exemplo("quero ver"),
                exemplo("cancelar"),
                exemplo("quero cancelar"),
                exemplo("remarcar"),
                exemplo("quero remarcar"),
                exemplo("confirmar consulta"),
                exemplo("confirmar presença"),
                exemplo("ver"),
                exemplo("3"),
                exemplo("opção 3"),
                exemplo("ver, remarcar ou cancelar"),
                exemplo("ver, remarcar e cancelar"),
                exemplo("ver remarcar ou cancelar"),
            ],
        ),
        intent(
            "acao.agenda.escolher",
            [FALHA],
            priority=850000,
            webhookState=webhook,
            inputContextNames=[ctx(project, "oferta")],
            parameters=[parametro("escolha", "@sys.number")],
            trainingPhrases=[numero(str(n)) for n in range(1, 9)] + [
                exemplo("o primeiro"),
                exemplo("o segundo"),
                exemplo("o terceiro"),
            ],
        ),
        intent(
            "acao.agenda.agendar",
            [FALHA],
            priority=900000,
            webhookState=webhook,
            inputContextNames=[ctx(project, "paciente"), ctx(project, "aguardando_confirmacao")],
            parameters=[parametro("confirmacao", "@confirmacao")],
            trainingPhrases=[
                exemplo(("sim", "@confirmacao", "confirmacao")),
                exemplo(("pode marcar", "@confirmacao", "confirmacao")),
                exemplo(("confirmo", "@confirmacao", "confirmacao")),
                exemplo(("quero", "@confirmacao", "confirmacao")),
                exemplo(("isso", "@confirmacao", "confirmacao")),
                exemplo(("não", "@confirmacao", "confirmacao")),
                exemplo(("não quero", "@confirmacao", "confirmacao")),
                exemplo("outro horário"),
                exemplo("1"),
                exemplo("2"),
            ],
        ),
        intent(
            "acao.agenda.confirmar",
            [FALHA],
            priority=880000,
            mlDisabled=True,
            webhookState=webhook,
            inputContextNames=[ctx(project, "paciente"), ctx(project, "agendamentos")],
            parameters=[parametro("escolha", "@sys.number")],
            trainingPhrases=frases_numero("confirmar") + frases_numero("confirma"),
        ),
        intent(
            "acao.agenda.cancelar",
            [FALHA],
            priority=880000,
            mlDisabled=True,
            webhookState=webhook,
            inputContextNames=[ctx(project, "paciente"), ctx(project, "agendamentos")],
            parameters=[parametro("escolha", "@sys.number")],
            trainingPhrases=frases_numero("cancelar") + frases_numero("cancela"),
        ),
        intent(
            "acao.agenda.remarcar",
            [FALHA],
            priority=880000,
            mlDisabled=True,
            webhookState=webhook,
            inputContextNames=[ctx(project, "paciente"), ctx(project, "agendamentos")],
            parameters=[parametro("escolha", "@sys.number")],
            trainingPhrases=frases_numero("remarcar") + frases_numero("remarca"),
        ),
        intent("texto.resultado.paciente_nao_encontrado", [
            "Não localizei um cadastro com o CPF e a data de nascimento informados.\n\n"
            "1. Agendar sem cadastro\n"
            "2. Conferir os dados\n\n"
            "Qual opção você prefere?"
        ], events=["RESULTADO_PACIENTE_NAO_ENCONTRADO"], mlDisabled=True, parameters=[
            parametro("cpf", "@sys.any", padrao="#identificando.cpf"),
            parametro("nascimento", "@sys.any", padrao="#identificando.data_nascimento"),
        ], outputContexts=[
            saida(project, "paciente_sem_cadastro", 20),
            saida(project, "opcao_sem_cadastro", 3),
            saida(project, "identificando", 0),
            saida(project, "menu", 0),
        ]),
        intent("texto.resultado.sucesso_sem_cadastro", [
            "Certo, $nome. Vou reservar sem criar cadastro de pessoa física."
        ], events=["RESULTADO_SUCESSO_SEM_CADASTRO"], mlDisabled=True, parameters=[
            parametro("nome", "@sys.any"),
        ], outputContexts=[
            saida(project, "apos_identificar", 4),
            saida(project, "menu", 0),
        ]),
        intent("texto.resultado.nao_identificado", [
            "Para seguir, preciso da data de nascimento e do CPF."
        ], events=["RESULTADO_NAO_IDENTIFICADO"], mlDisabled=True, outputContexts=[
            saida(project, "menu", 3),
        ]),
        intent("texto.resultado.sucesso_identificar", [
            "Encontrei o cadastro de $nome.\n\nDiga continuar para marcar, ou ver, remarcar e cancelar."
        ], events=["RESULTADO_SUCESSO_IDENTIFICAR"], mlDisabled=True, parameters=[
            parametro("nome", "@sys.any"),
        ], outputContexts=[
            saida(project, "apos_identificar", 4),
            saida(project, "menu", 0),
        ]),
        intent("texto.resultado.ja_tem_agendamento", [
            "$nome, você já tem consulta em $quando. Posso marcar outra, ver, remarcar ou cancelar."
        ], events=["RESULTADO_JA_TEM_AGENDAMENTO"], mlDisabled=True, parameters=[
            parametro("nome", "@sys.any"),
            parametro("quando", "@sys.any"),
            parametro("quantidade", "@sys.any"),
        ], outputContexts=[
            saida(project, "apos_identificar", 4),
            saida(project, "menu", 0),
        ]),
        intent("texto.resultado.tipo_busca", [
            "Como você prefere procurar o atendimento?\n\n"
            "1. Pela clínica ou especialidade\n"
            "2. Por um médico de preferência\n\n"
            "Responda com o número ou com a opção desejada."
        ], events=["RESULTADO_TIPO_BUSCA"], mlDisabled=True, outputContexts=[
            saida(project, "tipo_busca", 3),
            saida(project, "lista", 0),
        ]),
        intent("texto.resultado.lista_convenio", [
            "Qual convênio você gostaria de usar?\n\n"
            "$lista\n\n"
            "Responda com o número ou o nome da opção. Para retornar, diga voltar."
        ], events=["RESULTADO_LISTA_CONVENIO"], mlDisabled=True, parameters=[
            parametro("lista", "@sys.any"),
            parametro("payload", "@sys.any"),
        ], outputContexts=[
            saida(project, "lista", 3),
            saida(project, "opcao_sem_cadastro", 0),
            saida(project, "apos_identificar", 0),
            saida(project, "tipo_busca", 0),
        ]),
        intent("texto.resultado.lista_profissional", [
            "Você gostaria de marcar com algum destes profissionais?\n\n"
            "$lista\n\n"
            "Responda com o número ou o nome da opção."
        ], events=["RESULTADO_LISTA_PROFISSIONAL"], mlDisabled=True, parameters=[
            parametro("lista", "@sys.any"),
            parametro("payload", "@sys.any"),
        ], outputContexts=[
            saida(project, "lista", 3),
            saida(project, "opcao_sem_cadastro", 0),
            saida(project, "apos_identificar", 0),
            saida(project, "tipo_busca", 0),
        ]),
        intent("texto.resultado.lista_exame", [
            "Qual exame você precisa marcar?\n\n"
            "$lista\n\n"
            "Responda com o número ou o nome do exame. Para retornar, diga voltar."
        ], events=["RESULTADO_LISTA_EXAME"], mlDisabled=True, parameters=[
            parametro("lista", "@sys.any"),
            parametro("payload", "@sys.any"),
        ], outputContexts=[
            saida(project, "lista", 3),
            saida(project, "opcao_sem_cadastro", 0),
            saida(project, "apos_identificar", 0),
            saida(project, "tipo_busca", 0),
        ]),
        intent("texto.resultado.sem_exame", [
            "No momento não encontrei exames com horário livre para marcar por aqui. "
            "Se quiser, diga atendente para falar com alguém da equipe."
        ], events=["RESULTADO_SEM_EXAME"], mlDisabled=True),
        intent("texto.resultado.sem_horario_exame", [
            "Não encontrei horários de $exame $periodo.\n\n"
            "Como você prefere continuar?\n\n"
            "1. Tentar outro período\n"
            "2. Escolher outro exame\n"
            "3. Falar com alguém da equipe"
        ], events=["RESULTADO_SEM_HORARIO_EXAME"], mlDisabled=True, parameters=[
            parametro("exame", "@sys.any"),
            parametro("periodo", "@sys.any"),
        ], outputContexts=[
            saida(project, "sem_horario_exame", 3),
            saida(project, "pedindo_horario", 0),
        ]),
        intent("texto.resultado.uma_especialidade", [
            "Com $medico, a especialidade é $especialidade. Sigo com ela?"
        ], events=["RESULTADO_UMA_ESPECIALIDADE"], mlDisabled=True, parameters=[
            parametro("medico", "@sys.any"),
            parametro("especialidade", "@sys.any"),
        ], outputContexts=[
            saida(project, "confirma_especialidade", 2),
        ]),
        intent("texto.resultado.lista_especialidade", [
            "Qual especialidade você gostaria de consultar?\n\n"
            "$lista\n\n"
            "Responda com o número ou o nome da opção. Para retornar, diga voltar."
        ], events=["RESULTADO_LISTA_ESPECIALIDADE"], mlDisabled=True, parameters=[
            parametro("lista", "@sys.any"),
            parametro("medico", "@sys.any"),
            parametro("payload", "@sys.any"),
        ], outputContexts=[
            saida(project, "lista", 3),
            saida(project, "opcao_sem_cadastro", 0),
            saida(project, "apos_identificar", 0),
            saida(project, "tipo_busca", 0),
        ]),
        intent("texto.resultado.lista_horarios", [
            "$lista"
        ], events=["RESULTADO_LISTA_HORARIOS"], mlDisabled=True, parameters=[
            parametro("lista", "@sys.any"),
            parametro("payload", "@sys.any"),
        ], outputContexts=[
            saida(project, "oferta", 5),
            saida(project, "lista", 0),
            saida(project, "tipo_busca", 0),
            saida(project, "pedindo_horario", 0),
        ]),
        intent("texto.resultado.sucesso_lista", [
            "$lista"
        ], events=["RESULTADO_SUCESSO_LISTA"], mlDisabled=True, parameters=[
            parametro("lista", "@sys.any"),
            parametro("payload", "@sys.any"),
        ], outputContexts=[
            saida(project, "lista", 3),
            saida(project, "opcao_sem_cadastro", 0),
            saida(project, "apos_identificar", 0),
            saida(project, "pedindo_horario", 0),
        ]),
        intent("texto.resultado.sem_horario", [
            "Não encontrei horários de $especialidade $periodo.\n\n"
            "Como você prefere continuar?\n\n"
            "1. Tentar outro período\n"
            "2. Escolher outra especialidade ou médico\n"
            "3. Falar com alguém da equipe"
        ], events=["RESULTADO_SEM_HORARIO"], mlDisabled=True, parameters=[
            parametro("especialidade", "@sys.any"),
            parametro("periodo", "@sys.any"),
        ], outputContexts=[
            saida(project, "sem_horario", 3),
            saida(project, "pedindo_horario", 0),
        ]),
        intent("texto.resultado.sem_agendamento", [
            "$nome, não encontrei consulta em aberto no seu nome. Se você marcou por telefone ou na recepção, posso chamar alguém da equipe para conferir."
        ], events=["RESULTADO_SEM_AGENDAMENTO"], mlDisabled=True, parameters=[
            parametro("nome", "@sys.any"),
        ]),
        intent("texto.resultado.confirmacao_ausente", [
            "$texto"
        ], events=["RESULTADO_CONFIRMACAO_AUSENTE"], mlDisabled=True, parameters=[
            parametro("texto", "@sys.any"),
            parametro("resumo", "@sys.any"),
            parametro("acao", "@sys.any"),
        ], outputContexts=[
            saida(project, "pedindo_horario", 0),
        ]),
        intent("texto.resultado.horario_fora_da_oferta", [
            "Escolha um dos horários que acabei de passar."
        ], events=["RESULTADO_HORARIO_FORA_DA_OFERTA"], mlDisabled=True),
        intent("texto.resultado.horario_indisponivel", [
            "Esse horário não está mais livre."
        ], events=["RESULTADO_HORARIO_INDISPONIVEL"], mlDisabled=True),
        intent("texto.resultado.agendamento_nao_encontrado", [
            "Não encontrei essa consulta."
        ], events=["RESULTADO_AGENDAMENTO_NAO_ENCONTRADO"], mlDisabled=True),
        intent("texto.resultado.sucesso_escrita", [
            "$texto",
            "$orientacao",
            "$fechamento",
        ], events=["RESULTADO_SUCESSO_ESCRITA"], mlDisabled=True, parameters=[
            parametro("texto", "@sys.any"),
            parametro("orientacao", "@sys.any"),
            parametro("fechamento", "@sys.any"),
            parametro("especialidade", "@sys.any"),
            parametro("data", "@sys.any"),
            parametro("hora", "@sys.any"),
            parametro("medico", "@sys.any"),
            parametro("convenio", "@sys.any"),
            parametro("acao", "@sys.any"),
        ], outputContexts=[
            saida(project, "pos_agendamento", 3),
            saida(project, "oferta", 0),
            saida(project, "lista", 0),
        ]),
        intent("texto.resultado.indisponivel", [
            "Não consegui consultar agora. Pode tentar de novo ou pedir um atendente."
        ], events=["RESULTADO_INDISPONIVEL"], mlDisabled=True),
        intent("texto.resultado.faq", [
            "$texto"
        ], events=["RESULTADO_FAQ"], mlDisabled=True, parameters=[
            parametro("texto", "@sys.any"),
        ]),
    ] + perguntas_frequentes()


# O texto de cada tema vem da configuração da clínica (mandalore.faq.<tema>), pelo webhook.
PERGUNTAS_FREQUENTES = {
    "endereco": [
        "qual o endereço",
        "qual o endereco de vocês",
        "onde fica a clínica",
        "onde fica o laboratório",
        "onde vocês ficam",
        "como chego aí",
        "como faço para chegar",
        "qual a localização",
        "me passa o endereço",
        "fica em qual rua",
    ],
    "horario": [
        "qual o horário de atendimento",
        "que horas vocês abrem",
        "que horas fecha",
        "até que horas vocês atendem",
        "vocês abrem sábado",
        "funciona aos domingos",
        "abre no feriado",
        "horário de funcionamento",
        "qual o horário da coleta",
        "que horas começa a coleta",
    ],
    "convenios": [
        "quais convênios vocês aceitam",
        "quais convenios aceitam",
        "vocês aceitam unimed",
        "atende pelo meu plano de saúde",
        "aceitam plano de saúde",
        "trabalham com quais planos",
        "quais planos vocês atendem",
        "meu convênio é aceito",
        "vocês atendem particular",
    ],
    "preparo": [
        "precisa de jejum",
        "preciso estar em jejum",
        "quantas horas de jejum",
        "qual o jejum para exame de sangue",
        "como me preparo para o exame",
        "qual o preparo do exame",
        "posso tomar água antes do exame",
        "posso tomar meu remédio antes da coleta",
        "como colher a urina",
        "como faço a coleta de fezes",
    ],
    "resultado": [
        "quando sai o resultado",
        "qual o prazo do resultado",
        "quanto tempo demora o resultado",
        "como pego meu resultado",
        "onde vejo o resultado do exame",
        "o resultado sai pela internet",
        "como acesso o laudo",
        "como retirar o resultado",
    ],
    "documentos": [
        "o que preciso levar",
        "quais documentos devo levar",
        "preciso levar o pedido médico",
        "preciso levar a carteirinha do convênio",
        "precisa de documento com foto",
        "o que levar no dia do exame",
    ],
    "estacionamento": [
        "tem estacionamento",
        "onde posso estacionar",
        "o estacionamento é pago",
        "tem vaga para carro",
        "onde deixo o carro",
    ],
    "contato": [
        "qual o telefone",
        "qual o telefone de vocês",
        "tem um número para ligar",
        "qual o email de vocês",
        "como falo com vocês por telefone",
        "qual o whatsapp",
    ],
    "valores": [
        "quanto custa o exame",
        "qual o valor da consulta",
        "quanto fica o hemograma",
        "qual o preço do exame",
        "quanto custa particular",
        "vocês fazem orçamento",
    ],
}


def perguntas_frequentes() -> list:
    return [
        intent(
            f"acao.faq.{tema}",
            [FALHA],
            action="faq",
            webhookState="WEBHOOK_STATE_ENABLED",
            trainingPhrases=[exemplo(frase) for frase in frases],
        )
        for tema, frases in PERGUNTAS_FREQUENTES.items()
    ]


def ler_env(caminho: Path) -> dict:
    if not caminho.exists():
        return {}
    valores = {}
    for linha in caminho.read_text(encoding="utf-8").splitlines():
        texto_linha = linha.strip()
        if not texto_linha or texto_linha.startswith("#") or "=" not in texto_linha:
            continue
        chave, valor = texto_linha.split("=", 1)
        valores[chave.strip()] = valor.strip()
    return valores


def gravar_env(caminho: Path, valores: dict) -> None:
    existentes = caminho.read_text(encoding="utf-8").splitlines() if caminho.exists() else []
    atualizacoes = {
        "DIALOGFLOW_PROJECT_ID": valores["DIALOGFLOW_PROJECT_ID"],
        "DIALOGFLOW_CREDENTIALS": valores["DIALOGFLOW_CREDENTIALS"],
        "DIALOGFLOW_WEBHOOK_USER": valores["DIALOGFLOW_WEBHOOK_USER"],
        "DIALOGFLOW_WEBHOOK_PASSWORD": valores["DIALOGFLOW_WEBHOOK_PASSWORD"],
        "DIALOGFLOW_WEBHOOK_URL": valores.get("DIALOGFLOW_WEBHOOK_URL", ""),
    }
    linhas = []
    atualizadas = set()
    for linha in existentes:
        texto_linha = linha.strip()
        if texto_linha and not texto_linha.startswith("#") and "=" in texto_linha:
            chave = texto_linha.split("=", 1)[0].strip()
            if chave in atualizacoes:
                linhas.append(f"{chave}={atualizacoes[chave]}")
                atualizadas.add(chave)
                continue
        linhas.append(linha)
    for chave, valor in atualizacoes.items():
        if chave not in atualizadas:
            linhas.append(f"{chave}={valor}")
    caminho.write_text("\n".join(linhas) + "\n", encoding="utf-8")


def vincular(desejados: list, existentes: list) -> None:
    por_nome = {item["displayName"]: item["name"] for item in existentes}
    welcome = next((item for item in existentes if "WELCOME" in item.get("events", [])), None)
    fallback = next(
        (
            item for item in existentes
            if item.get("isFallback") and not item.get("inputContextNames")
        ),
        None,
    )
    for desejado in desejados:
        if desejado["displayName"] in por_nome:
            desejado["name"] = por_nome[desejado["displayName"]]
            continue
        if "WELCOME" in desejado.get("events", []) and welcome:
            desejado["name"] = welcome["name"]
            continue
        if desejado.get("isFallback") and not desejado.get("inputContextNames") and fallback:
            desejado["name"] = fallback["name"]


def detectar(project: str, token: str, session: str, texto_usuario: str) -> str:
    _, resposta = call(
        "POST",
        f"{API}/projects/{project}/agent/sessions/{session}:detectIntent",
        token,
        {
            "queryInput": {
                "text": {"text": texto_usuario, "languageCode": "pt-BR"},
            },
            "queryParams": {"timeZone": "America/Sao_Paulo"},
        },
    )
    resultado = resposta.get("queryResult", {})
    nome = resultado.get("intent", {}).get("displayName", "")
    fala = resultado.get("fulfillmentText", "")
    return f"{nome} | {fala[:180]}"


def manifesto() -> dict:
    """Retorna o contrato determinístico do agente sem acessar Google ou credenciais."""
    return {
        "schemaVersion": 1,
        "languageCode": "pt-BR",
        "project": MANIFEST_PROJECT,
        "entities": sorted(entidades(), key=lambda item: item["displayName"]),
        "intents": sorted(
            montar_intents(MANIFEST_PROJECT),
            key=lambda item: item["displayName"],
        ),
    }


def exportar_manifesto(caminho: Path = MANIFEST_FILE) -> None:
    caminho.parent.mkdir(parents=True, exist_ok=True)
    caminho.write_text(
        json.dumps(manifesto(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"manifesto=exportado caminho={caminho}")


def conferir_manifesto(caminho: Path = MANIFEST_FILE) -> None:
    if not caminho.exists():
        raise SystemExit(
            f"Manifesto ausente em {caminho}. "
            "Execute com --export-manifest antes de rodar a regressão."
        )
    atual = json.loads(caminho.read_text(encoding="utf-8"))
    esperado = manifesto()
    if atual != esperado:
        print(
            "Manifesto desatualizado. Execute "
            "'py scripts/publicar_dialogflow.py --export-manifest' e revise o diff.",
            file=sys.stderr,
        )
        raise SystemExit(1)
    print(f"manifesto=atual caminho={caminho}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    grupo = parser.add_mutually_exclusive_group()
    grupo.add_argument(
        "--export-manifest",
        action="store_true",
        help="Exporta o contrato local do agente sem chamar o Dialogflow.",
    )
    grupo.add_argument(
        "--check-manifest",
        action="store_true",
        help="Falha se o manifesto versionado divergir das definições do agente.",
    )
    args = parser.parse_args()
    if args.export_manifest:
        exportar_manifesto()
        return
    if args.check_manifest:
        conferir_manifesto()
        return

    sa = json.loads(CREDENTIALS.read_text(encoding="utf-8"))
    project = sa.get("project_id") or PROJECT_FALLBACK
    token = access_token(sa)
    print(f"projeto={project}")

    try:
        _, agente = call("GET", f"{API}/projects/{project}/agent", token)
        print(f"agente=existente idioma={agente.get('defaultLanguageCode')}")
    except SystemExit as error:
        if "404" not in str(error):
            raise
        _, criado = call("POST", f"{API}/projects/{project}/agent", token, {
            "displayName": "Agendamento de consulta",
            "defaultLanguageCode": "pt-BR",
            "timeZone": "America/Sao_Paulo",
            "description": "Marcar, ver, confirmar, cancelar e remarcar consulta.",
            "enableLogging": True,
            "matchMode": "MATCH_MODE_HYBRID",
            "classificationThreshold": 0.3,
        })
        print(f"agente=criado idioma={criado.get('defaultLanguageCode')}")

    atuais = list_all(f"{API}/projects/{project}/agent/entityTypes?pageSize=1000", token, "entityTypes")
    por_entidade = {item["displayName"]: item["name"] for item in atuais}
    lote_entidades = []
    for entidade in entidades():
        if entidade["displayName"] in por_entidade:
            entidade["name"] = por_entidade[entidade["displayName"]]
        lote_entidades.append(entidade)
    operacao = call("POST", f"{API}/projects/{project}/agent/entityTypes:batchUpdate", token, {
        "entityTypeBatchInline": {"entityTypes": lote_entidades},
        "languageCode": "pt-BR",
    })[1]
    wait(operacao, token)
    print(f"entidades={len(lote_entidades)}")

    intents = montar_intents(project)
    existentes = list_all(f"{API}/projects/{project}/agent/intents?pageSize=1000&intentView=INTENT_VIEW_FULL", token, "intents")
    vincular(intents, existentes)
    operacao = call("POST", f"{API}/projects/{project}/agent/intents:batchUpdate", token, {
        "intentBatchInline": {"intents": intents},
        "languageCode": "pt-BR",
    })[1]
    wait(operacao, token)
    print(f"intents={len(intents)}")

    ambiente = ler_env(ENV_FILE)
    senha = ambiente.get("DIALOGFLOW_WEBHOOK_PASSWORD") or secrets.token_hex(18)
    usuario = ambiente.get("DIALOGFLOW_WEBHOOK_USER") or "dialogflow"
    url_webhook = ambiente.get("DIALOGFLOW_WEBHOOK_URL", "")
    gravar_env(ENV_FILE, {
        "DIALOGFLOW_PROJECT_ID": project,
        "DIALOGFLOW_CREDENTIALS": str(CREDENTIALS),
        "DIALOGFLOW_WEBHOOK_USER": usuario,
        "DIALOGFLOW_WEBHOOK_PASSWORD": senha,
        "DIALOGFLOW_WEBHOOK_URL": url_webhook,
    })
    if url_webhook.startswith("https://"):
        call(
            "PATCH",
            f"{API}/projects/{project}/agent/fulfillment?updateMask=displayName,enabled,genericWebService",
            token,
            {
                "displayName": "agendamento-conversa",
                "enabled": True,
                "genericWebService": {
                    "uri": url_webhook,
                    "username": usuario,
                    "password": senha,
                },
            },
        )
        print("fulfillment=ligado")
    else:
        print("fulfillment=desligado (defina DIALOGFLOW_WEBHOOK_URL no .env e rode de novo)")

    operacao = call("POST", f"{API}/projects/{project}/agent:train", token, {})[1]
    if operacao.get("name"):
        wait(operacao, token)
    print("treino=concluido")

    if ambiente.get("DIALOGFLOW_CREATE_VERSION", "false").lower() == "true":
        operacao = call("POST", f"{API}/projects/{project}/agent/versions", token, {
            "description": "Agendamento de consulta",
        })[1]
        if operacao.get("name"):
            try:
                concluida = wait(operacao, token)
                versao = concluida.get("response", {}).get("name", "")
                print(f"versao={versao}")
            except TimeoutError:
                print(f"versao=pendente operacao={operacao['name']}")
        else:
            print("versao=nao criada")
    else:
        print("versao=ignorada (agente draft)")

    print("detectIntent oi:", detectar(project, token, "demo-oi", "oi"))
    print("detectIntent atendente:", detectar(project, token, "demo-atendente", "quero falar com alguém"))


if __name__ == "__main__":
    main()
