package br.com.mandalore.agendamentoconversa.application.domain.port.in;

import java.time.LocalDate;

import br.com.mandalore.agendamentoconversa.application.domain.model.Relatorio;

public interface RelatorioUseCase {

    String texto(Relatorio.Tipo tipo);

    /** Números de um intervalo de dias (inclusive), para perguntas fora dos relatórios fixos. */
    Relatorio.Periodo periodo(LocalDate de, LocalDate ate);

    /** Os últimos {@code dias} dias, contando hoje (1 = só hoje), como o filtro da tela de curadoria. */
    Relatorio.Periodo ultimosDias(int dias);

    /** Envio direto pelo Telegram do Mandalore, quando RELATORIOS_TELEGRAM_ENABLED=true. */
    void enviar(Relatorio.Tipo tipo);
}
