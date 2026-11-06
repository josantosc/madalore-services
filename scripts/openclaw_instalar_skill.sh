#!/usr/bin/env bash
# Instala ou atualiza a skill mandalore-relatorios no OpenClaw de um cliente e reinicia o gateway.
#
# Uso (Linux, macOS ou WSL):
#   scripts/openclaw_instalar_skill.sh root@<ip-do-servidor> [chave-ssh] [container-gateway]
#
# A chave padrao e ~/.ssh/id_ed25519. Se ela tiver senha, o ssh pergunta (ou use ssh-agent).
# Com mais de um OpenClaw no servidor, informe o container (openclaw-<uuid>) do cliente.
# Nao grava nenhum segredo: so copia o SKILL.md para o volume /data do container do gateway.
set -euo pipefail

DESTINO_SSH="${1:?Informe o servidor, ex.: root@203.0.113.10}"
CHAVE="${2:-$HOME/.ssh/id_ed25519}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
SKILL="$RAIZ/deploy/openclaw/skills/mandalore-relatorios/SKILL.md"
PASTA=/data/workspace/skills/mandalore-relatorios

[ -f "$SKILL" ] || { echo "Nao encontrei $SKILL" >&2; exit 1; }
ssh_cliente() { ssh -i "$CHAVE" -o ConnectTimeout=15 "$DESTINO_SSH" "$@"; }

# O template do Coolify sobe dois containers: openclaw-<uuid> (gateway) e browser-<uuid> (navegador).
GATEWAYS="$(ssh_cliente "docker ps --format '{{.Names}}' | grep '^openclaw-' || true")"
GATEWAY="${3:-$GATEWAYS}"
[ -n "$GATEWAY" ] || { echo "Nenhum container openclaw-* rodando. O gateway subiu? Veja os logs no Coolify." >&2; exit 1; }
if [ "$(printf '%s\n' "$GATEWAY" | wc -l)" -gt 1 ]; then
  echo "Mais de um OpenClaw neste servidor. Informe o container do cliente como terceiro parametro:" >&2
  printf '%s\n' "$GATEWAYS" >&2
  exit 1
fi
printf '%s\n' "$GATEWAYS" | grep -qx "$GATEWAY" || { echo "Container $GATEWAY nao esta rodando." >&2; exit 1; }
echo "Gateway: $GATEWAY"

tr -d '\r' < "$SKILL" | ssh_cliente "docker exec -i $GATEWAY sh -c 'mkdir -p $PASTA && cat > $PASTA/SKILL.md'"
echo "Skill gravada em $PASTA/SKILL.md"

ssh_cliente "docker restart $GATEWAY >/dev/null"
echo "Gateway reiniciado. Aguardando subir..."
for _ in $(seq 1 18); do
  sleep 5
  if ssh_cliente "docker exec $GATEWAY sh -c 'timeout 60 openclaw skills list 2>/dev/null'" | grep -q 'mandalore-'; then
    ssh_cliente "docker exec $GATEWAY sh -c 'timeout 60 openclaw skills list 2>/dev/null'" | grep -A1 'mandalore-'
    ssh_cliente "docker exec $GATEWAY sh -c 'command -v curl >/dev/null && echo curl: ok || echo curl: AUSENTE - a skill nao funciona sem curl'"
    exit 0
  fi
done
echo "A skill nao apareceu em 'openclaw skills list'. Confira MANDALORE_URL e MANDALORE_OPENCLAW_TOKEN no OpenClaw." >&2
exit 1
