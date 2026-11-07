package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import br.com.mandalore.agendamentoconversa.application.domain.model.Curadoria;

/**
 * Ajuda a escolher a intent na curadoria: descrição em português, o momento da conversa em que ela vale e as
 * intents mais parecidas com a fala. A semelhança é por palavras e pedaços de palavras das frases de treino; é
 * uma sugestão para a pessoa conferir, não uma classificação.
 */
final class GuiaIntents {

    static final int MAX_SUGESTOES = 3;
    static final int MAX_EXEMPLOS = 5;
    /** Abaixo disso a sugestão mais atrapalha do que ajuda. */
    static final int SEMELHANCA_MINIMA = 25;

    private static final Map<String, String> DESCRICOES = Map.ofEntries(
            Map.entry("texto.saudacao", "Cumprimento ou pedido do menu inicial (oi, bom dia, menu)"),
            Map.entry("texto.saudacao.retomar", "Cumprimento de quem já se identificou: mostra o menu de novo"),
            Map.entry("texto.conversa.reiniciar", "Recomeçar a conversa do zero"),
            Map.entry("texto.conversa.encerrar", "Encerrar o atendimento (tchau, era só isso); resolve a conversa"),
            Map.entry("texto.conversa.encerrar.pos_agendamento", "Encerrar logo depois de marcar"),
            Map.entry("texto.conversa.agradecimento", "Agradecimento (obrigado, valeu); não encerra"),
            Map.entry("texto.navegacao.voltar", "Voltar ao passo anterior"),
            Map.entry("texto.menu.orcamento", "Opção 4 do menu: orçamento de exames"),
            Map.entry("texto.menu.orcamento.retomar", "Orçamento de exames no menu de quem já se identificou"),
            Map.entry("texto.coleta.nascimento", "Opção do menu: marcar consulta ou ver agendamentos"),
            Map.entry("texto.coleta.cpf", "Paciente não sabe ou pergunta qual CPF informar"),
            Map.entry("texto.exame.iniciar", "Opção do menu: marcar exame"),
            Map.entry("texto.consulta.marcar_outro", "Marcar outra consulta quando já tem uma marcada"),
            Map.entry("texto.consulta.seguir_especialidade", "Confirma a especialidade sugerida"),
            Map.entry("texto.paciente.tentar_novamente", "CPF não encontrado: tentar outro CPF"),
            Map.entry("texto.sem_horario.nova_busca", "Sem horário: fazer uma nova busca"),
            Map.entry("texto.sem_horario.outro_periodo", "Sem horário: tentar outro período"),
            Map.entry("texto.sem_horario_exame.outro_periodo", "Sem horário de exame: tentar outro período"),
            Map.entry("transbordo.atendente", "Pedido para falar com uma pessoa da equipe"),
            Map.entry("transbordo.sem_horario.atendente", "Sem horário: falar com a equipe"),
            Map.entry("transbordo.sem_horario_exame.atendente", "Sem horário de exame: falar com a equipe"),
            Map.entry("acao.paciente.identificar", "Paciente informa CPF e data de nascimento"),
            Map.entry("acao.paciente.sem_cadastro", "Seguir sem cadastro quando o CPF não foi encontrado"),
            Map.entry("acao.agenda.meus_agendamentos", "Ver os agendamentos que o paciente já tem"),
            Map.entry("acao.agenda.agendar", "Confirma o horário escolhido para marcar"),
            Map.entry("acao.agenda.cancelar", "Cancelar um agendamento da lista"),
            Map.entry("acao.agenda.confirmar", "Confirmar presença num agendamento da lista"),
            Map.entry("acao.agenda.remarcar", "Remarcar um agendamento da lista"),
            Map.entry("acao.agenda.disponibilidade", "Informa dia ou período para buscar horários"),
            Map.entry("acao.agenda.escolher", "Escolhe um dos horários oferecidos"),
            Map.entry("acao.consulta.convenios", "Marcar consulta: mostra os convênios"),
            Map.entry("acao.consulta.novo.convenios", "Marcar outra consulta depois de agendar"),
            Map.entry("acao.consulta.escolher_lista", "Escolhe um item da lista numerada (convênio, especialidade...)"),
            Map.entry("acao.consulta.especialidades", "Buscar consulta por especialidade"),
            Map.entry("acao.consulta.profissionais", "Buscar consulta por médico de preferência"),
            Map.entry("acao.exame.convenios", "Marcar exame: mostra os convênios"),
            Map.entry("acao.exame.exames", "Escolher outro exame quando não há horário"),
            Map.entry("acao.faq.valores", "Pergunta de preço ou orçamento"));

    private static final Map<String, String> MOMENTOS = Map.ofEntries(
            Map.entry("menu", "logo depois do menu inicial"),
            Map.entry("paciente", "com o paciente já identificado"),
            Map.entry("apos_identificar", "no menu depois de identificar o paciente"),
            Map.entry("pos_agendamento", "logo depois de um agendamento"),
            Map.entry("agendamentos", "com a lista de agendamentos do paciente na tela"),
            Map.entry("lista", "quando o bot mostrou uma lista numerada"),
            Map.entry("oferta", "quando o bot ofereceu horários"),
            Map.entry("pedindo_horario", "quando o bot pediu dia ou período"),
            Map.entry("periodo", "depois de escolher o período"),
            Map.entry("sem_horario", "quando não havia horário de consulta"),
            Map.entry("sem_horario_exame", "quando não havia horário de exame"),
            Map.entry("convenio", "com o convênio escolhido"),
            Map.entry("tipo_busca", "na escolha entre especialidade e médico"),
            Map.entry("aguardando_confirmacao", "confirmando o horário"),
            Map.entry("opcao_sem_cadastro", "quando o CPF não foi encontrado"),
            Map.entry("agendamento_futuro", "quando o paciente já tem consulta marcada"),
            Map.entry("confirma_especialidade", "confirmando a especialidade sugerida"));

    private GuiaIntents() {
    }

    static List<Curadoria.IntentInfo> catalogo(List<Curadoria.IntentAgente> intents) {
        return intents.stream()
                .filter(Curadoria.IntentAgente::ensinavel)
                .sorted(Comparator.comparing(Curadoria.IntentAgente::nome, String.CASE_INSENSITIVE_ORDER))
                .map(i -> new Curadoria.IntentInfo(i.nome(), descricao(i.nome()),
                        i.frases().stream().limit(MAX_EXEMPLOS).toList(), momento(i.contextos())))
                .toList();
    }

    static List<Curadoria.Sugestao> sugestoes(String fala, List<Curadoria.IntentAgente> intents) {
        String alvo = normalizar(fala);
        if (alvo.isEmpty()) {
            return List.of();
        }
        List<Curadoria.Sugestao> lista = new ArrayList<>();
        for (Curadoria.IntentAgente intent : intents) {
            if (!intent.ensinavel()) {
                continue;
            }
            double melhor = 0;
            String exemplo = null;
            for (String frase : intent.frases()) {
                double s = semelhanca(alvo, normalizar(frase));
                if (s > melhor) {
                    melhor = s;
                    exemplo = frase;
                }
            }
            int pontos = (int) Math.round(melhor * 100);
            if (pontos >= SEMELHANCA_MINIMA) {
                lista.add(new Curadoria.Sugestao(intent.nome(), descricao(intent.nome()), pontos, exemplo,
                        momento(intent.contextos())));
            }
        }
        lista.sort(Comparator.comparingInt(Curadoria.Sugestao::semelhanca).reversed()
                .thenComparing(s -> s.momento() != null)
                .thenComparing(Curadoria.Sugestao::intent));
        return lista.stream().limit(MAX_SUGESTOES).toList();
    }

    static String descricao(String intent) {
        String fixa = DESCRICOES.get(intent);
        if (fixa != null) {
            return fixa;
        }
        if (intent.startsWith(PerguntasFrequentesService.PREFIXO)) {
            return "Pergunta frequente: " + intent.substring(PerguntasFrequentesService.PREFIXO.length())
                    + " (responde o texto do faq.yaml da clínica)";
        }
        return null;
    }

    /** Em que ponto da conversa a intent vale; null quando vale a qualquer momento. */
    static String momento(List<String> contextos) {
        if (contextos == null || contextos.isEmpty()) {
            return null;
        }
        Set<String> partes = new LinkedHashSet<>();
        contextos.forEach(c -> partes.add(MOMENTOS.getOrDefault(c, c)));
        return "Só vale " + String.join(" e ", partes);
    }

    /** Média entre palavras em comum e trigramas de letras (pega erro de digitação: "brigado" e "obrigado"). */
    static double semelhanca(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        return 0.5 * jaccard(palavras(a), palavras(b)) + 0.5 * dice(trigramas(a), trigramas(b));
    }

    static String normalizar(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return semAcento.toLowerCase(Locale.ROOT).replaceAll("<\\w+>", " ").replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static Set<String> palavras(String texto) {
        return Arrays.stream(texto.split(" ")).filter(p -> !p.isEmpty()).collect(Collectors.toSet());
    }

    private static Set<String> trigramas(String texto) {
        String t = " " + texto + " ";
        Set<String> grams = new HashSet<>();
        for (int i = 0; i + 3 <= t.length(); i++) {
            grams.add(t.substring(i, i + 3));
        }
        return grams;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> comum = new HashSet<>(a);
        comum.retainAll(b);
        Set<String> uniao = new HashSet<>(a);
        uniao.addAll(b);
        return uniao.isEmpty() ? 0 : (double) comum.size() / uniao.size();
    }

    private static double dice(Set<String> a, Set<String> b) {
        Set<String> comum = new HashSet<>(a);
        comum.retainAll(b);
        return a.size() + b.size() == 0 ? 0 : 2.0 * comum.size() / (a.size() + b.size());
    }
}
