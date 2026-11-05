package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.RelatorioUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.NotificacaoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.RelatorioPort;

/**
 * Resumo diário da curadoria (ontem) e relatório semanal do gestor (segunda a domingo anteriores).
 * Só números: fala de paciente não sai da central.
 */
@Service
@ConditionalOnProperty(name = {"relatorios.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class RelatorioService implements RelatorioUseCase {

    static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DIA_CURTO = DateTimeFormatter.ofPattern("dd/MM");
    private static final Map<String, String> EQUIPES = Map.of(
            "RECEPCAO", "Recepção",
            "LABORATORIO", "Laboratório",
            "FATURAMENTO", "Faturamento");

    private final RelatorioPort relatorio;
    private final NotificacaoPort notificacao;
    private final Clock clock;
    private final double limiteConfianca;
    private final String nome;
    private final String url;

    @Autowired
    public RelatorioService(
            RelatorioPort relatorio,
            NotificacaoPort notificacao,
            @Value("${curadoria.limite-confianca:0.6}") double limiteConfianca,
            @Value("${admin.nome:}") String nome,
            @Value("${relatorios.url:}") String url
    ) {
        this(relatorio, notificacao, Clock.system(FUSO), limiteConfianca, nome, url);
    }

    RelatorioService(
            RelatorioPort relatorio,
            NotificacaoPort notificacao,
            Clock clock,
            double limiteConfianca,
            String nome,
            String url
    ) {
        this.relatorio = relatorio;
        this.notificacao = notificacao;
        this.clock = clock;
        this.limiteConfianca = limiteConfianca;
        this.nome = nome == null ? "" : nome.trim();
        this.url = url == null ? "" : url.trim().replaceAll("/$", "");
    }

    @Override
    public String texto(Relatorio.Tipo tipo) {
        return tipo == Relatorio.Tipo.GESTOR ? gestor() : curadoria();
    }

    @Override
    public void enviar(Relatorio.Tipo tipo) {
        notificacao.enviar(texto(tipo));
    }

    String curadoria() {
        LocalDate ontem = LocalDate.now(clock).minusDays(1);
        Instant inicio = inicio(ontem);
        Relatorio.Numeros dia = relatorio.numeros(inicio, inicio(ontem.plusDays(1)), limiteConfianca);
        Relatorio.Numeros semana = relatorio.numeros(inicio(ontem.minusDays(7)), inicio, limiteConfianca);

        StringBuilder texto = new StringBuilder(titulo("Curadoria do bot"))
                .append("Ontem, ").append(DIA.format(ontem)).append("\n\n");
        if (dia.mensagens() == 0) {
            texto.append("Nenhuma mensagem para o bot.\n");
        } else {
            texto.append("Mensagens: ").append(dia.mensagens()).append(" em ").append(dia.conversas()).append(" conversas\n")
                    .append("Não entendidas: ").append(dia.naoEntendidas())
                    .append(" (").append(pct(dia.naoEntendidas(), dia.mensagens())).append(")")
                    .append(". Média dos 7 dias anteriores: ").append(pct(semana.naoEntendidas(), semana.mensagens())).append('\n')
                    .append("Confiança baixa: ").append(dia.baixaConfianca())
                    .append(" (").append(pct(dia.baixaConfianca(), dia.mensagens())).append(")\n")
                    .append("Perguntas frequentes respondidas: ").append(dia.faqRespondidas()).append('\n');
        }
        texto.append("Na fila de curadoria (7 dias): ").append(dia.pendentes()).append('\n')
                .append("Frases ensinadas: ").append(dia.ensinadas()).append('\n');
        if (!dia.faqSemResposta().isEmpty()) {
            texto.append("Perguntas frequentes sem texto: ").append(lista(dia.faqSemResposta(), Map.of())).append('\n');
        }
        if (!dia.falhas().isEmpty()) {
            texto.append("Falhas registradas: ").append(lista(dia.falhas(), Map.of())).append('\n');
        }
        if (!url.isEmpty()) {
            texto.append("\nRevisar a fila: ").append(url).append("/curadoria");
        }
        return texto.toString().strip();
    }

    String gestor() {
        LocalDate segunda = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate inicioSemana = segunda.minusWeeks(1);
        Relatorio.Numeros atual = relatorio.numeros(inicio(inicioSemana), inicio(segunda), limiteConfianca);
        Relatorio.Numeros anterior = relatorio.numeros(inicio(inicioSemana.minusWeeks(1)), inicio(inicioSemana), limiteConfianca);

        StringBuilder texto = new StringBuilder(titulo("Relatório semanal do bot"))
                .append(DIA_CURTO.format(inicioSemana)).append(" a ").append(DIA.format(segunda.minusDays(1)))
                .append(". Entre parênteses, a semana anterior.\n\n")
                .append(linha("Conversas", atual.conversas(), anterior.conversas()))
                .append(linha("Consultas marcadas", atual.escritas("consulta"), anterior.escritas("consulta")))
                .append(linha("Exames marcados", atual.escritas("exame"), anterior.escritas("exame")))
                .append(linha("Confirmações", atual.escritas("confirmar"), anterior.escritas("confirmar")))
                .append(linha("Cancelamentos", atual.escritas("cancelar"), anterior.escritas("cancelar")))
                .append(linha("Remarcações", atual.escritas("remarcar"), anterior.escritas("remarcar")))
                .append(linha("Passadas para atendente", atual.totalTransbordos(), anterior.totalTransbordos()));
        if (!atual.transbordos().isEmpty()) {
            texto.append("  ").append(lista(atual.transbordos(), EQUIPES)).append('\n');
        }
        texto.append(linha("Pedidos de orçamento por foto", atual.orcamentos(), anterior.orcamentos()))
                .append(linha("Perguntas frequentes respondidas", atual.faqRespondidas(), anterior.faqRespondidas()))
                .append("Mensagens não entendidas: ").append(pct(atual.naoEntendidas(), atual.mensagens()))
                .append(" (").append(pct(anterior.naoEntendidas(), anterior.mensagens())).append(")");
        return texto.toString();
    }

    private String titulo(String titulo) {
        return titulo + (nome.isEmpty() ? "" : " - " + nome) + "\n";
    }

    private static Instant inicio(LocalDate dia) {
        return dia.atStartOfDay(FUSO).toInstant();
    }

    private static String linha(String rotulo, long atual, long anterior) {
        return rotulo + ": " + atual + " (" + anterior + ")\n";
    }

    private static String pct(long parte, long total) {
        return total == 0 ? "-" : String.format(PT_BR, "%.1f%%", 100.0 * parte / total);
    }

    private static String lista(Map<String, Long> contagem, Map<String, String> rotulos) {
        StringJoiner itens = new StringJoiner(", ");
        contagem.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> itens.add(rotulos.getOrDefault(e.getKey(), e.getKey()) + " " + e.getValue()));
        return itens.toString();
    }
}
