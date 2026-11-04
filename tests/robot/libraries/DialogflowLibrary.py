"""Keywords para conversar com o agente Dialogflow ES publicado.

Reaproveita a autenticação e o cliente HTTP de scripts/publicar_dialogflow.py.
"""

import html
import json
import os
import sys
import uuid
from pathlib import Path

from robot.api import logger
from robot.api.deco import keyword, library

RAIZ = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(RAIZ / "scripts"))

import publicar_dialogflow as dialogflow  # noqa: E402


@library(scope="SUITE", auto_keywords=False)
class DialogflowLibrary:

    def __init__(self):
        self._project = None
        self._token = None
        self._sessao = None
        self._resultado = {}

    def _conectar(self):
        if self._token:
            return
        ambiente = dialogflow.ler_env(dialogflow.ENV_FILE)
        credencial = Path(
            os.environ.get("DIALOGFLOW_CREDENTIALS")
            or ambiente.get("DIALOGFLOW_CREDENTIALS")
            or dialogflow.CREDENTIALS
        )
        if not credencial.exists():
            raise AssertionError(
                "Credencial do Dialogflow não encontrada. Defina DIALOGFLOW_CREDENTIALS no .env."
            )
        conta = json.loads(credencial.read_text(encoding="utf-8"))
        self._project = (
            os.environ.get("DIALOGFLOW_PROJECT_ID")
            or ambiente.get("DIALOGFLOW_PROJECT_ID")
            or conta.get("project_id")
        )
        self._token = dialogflow.access_token(conta)

    @keyword("Iniciar Nova Conversa")
    def iniciar_nova_conversa(self):
        """Abre uma sessão nova, sem contextos de testes anteriores."""
        self._conectar()
        self._sessao = "robot-" + uuid.uuid4().hex[:16]
        self._resultado = {}
        return self._sessao

    @keyword("Usuário Envia")
    def usuario_envia(self, texto):
        """Envia a frase ao agente na sessão atual e registra o turno no log."""
        if not self._sessao:
            self.iniciar_nova_conversa()
        try:
            _, resposta = dialogflow.call(
                "POST",
                f"{dialogflow.API}/projects/{self._project}/agent/sessions/{self._sessao}:detectIntent",
                self._token,
                {
                    "queryInput": {"text": {"text": texto, "languageCode": "pt-BR"}},
                    "queryParams": {"timeZone": "America/Sao_Paulo"},
                },
            )
        except SystemExit as erro:
            raise AssertionError(f"Falha ao chamar o Dialogflow: {erro}") from None
        self._resultado = resposta.get("queryResult", {})
        intent = self._resultado.get("intent", {}).get("displayName", "")
        fala = self._resultado.get("fulfillmentText", "")
        logger.info(
            "<table>"
            f"<tr><td><b>Usuário</b></td><td>{html.escape(texto)}</td></tr>"
            f"<tr><td><b>Intent</b></td><td>{html.escape(intent)}</td></tr>"
            f"<tr><td><b>Bot</b></td><td><pre>{html.escape(fala)}</pre></td></tr>"
            "</table>",
            html=True,
        )
        return fala

    @keyword("Intent Deve Ser")
    def intent_deve_ser(self, esperada):
        obtida = self._resultado.get("intent", {}).get("displayName", "")
        if obtida != esperada:
            raise AssertionError(
                f"Intent esperada '{esperada}', obtida '{obtida}'. "
                f"Resposta: {self._resultado.get('fulfillmentText', '')!r}"
            )

    @keyword("Resposta Deve Conter")
    def resposta_deve_conter(self, trecho):
        fala = self._resultado.get("fulfillmentText", "")
        if trecho.casefold() not in fala.casefold():
            raise AssertionError(f"Resposta não contém '{trecho}': {fala!r}")
