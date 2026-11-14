package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort.ProfissionalClinico;

/**
 * Atestado e declaração de comparecimento: troca dos marcadores ({paciente}, {data}, {chegada}...) e a folha impressa.
 * A tela troca o que já sabe ao montar o texto; o que sobrar é trocado aqui, na emissão. {data} é o dia do
 * atendimento (documento emitido depois continua com o dia da consulta); {hoje}, o da emissão.
 */
final class Documentos {

    static final int TEXTO_MAX = 4000;
    static final int DIAS_MAX = 365;
    static final String ACAO_IMPRESSAO = "IMPRESSAO_DOCUMENTO";

    private static final Pattern MARCADOR = Pattern.compile("\\{([a-z_]+)\\}");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private Documentos() {
    }

    /** Valores dos marcadores. prof nulo (recepção): {medico} é o profissional do atendimento e {crm} fica vazio. */
    static Map<String, String> marcadores(String clinica, Atendimento.Ficha ficha, Atendimento.PacienteResumo paciente,
                                          ProfissionalClinico prof, Integer dias, String cid, LocalDateTime agora) {
        Map<String, String> v = new HashMap<>();
        v.put("clinica", clinica);
        v.put("paciente", paciente.nome());
        v.put("cpf", cpf(paciente.cpf()));
        v.put("nascimento", paciente.nascimento() == null ? "" : paciente.nascimento().format(DATA));
        v.put("idade", idade(paciente, agora));
        v.put("data", (ficha.dtEntrada() == null ? agora : ficha.dtEntrada()).format(DATA));
        v.put("hoje", agora.format(DATA));
        v.put("hora", agora.format(HORA));
        v.put("chegada", ficha.dtEntrada() == null ? "" : ficha.dtEntrada().format(HORA));
        LocalDateTime saida = ficha.dtFimConsulta() != null ? ficha.dtFimConsulta()
                : ficha.dtAlta() != null ? ficha.dtAlta() : agora;
        v.put("saida", saida.format(HORA));
        v.put("medico", prof != null ? prof.nome() : ficha.profissional());
        v.put("crm", prof == null ? "" : conselho(prof));
        v.put("especialidade", ficha.especialidade());
        v.put("dias", dias == null ? "" : String.valueOf(dias));
        v.put("cid", cid == null ? "" : cid);
        return v;
    }

    /** Troca {nome} pelo valor; marcador desconhecido fica como está, para o usuário ver que não foi trocado. */
    static String aplicar(String texto, Map<String, String> valores) {
        Matcher m = MARCADOR.matcher(texto == null ? "" : texto);
        StringBuilder s = new StringBuilder();
        while (m.find()) {
            String valor = valores.containsKey(m.group(1)) ? Objects.toString(valores.get(m.group(1)), "") : m.group();
            m.appendReplacement(s, Matcher.quoteReplacement(valor));
        }
        m.appendTail(s);
        return s.toString();
    }

    /** Texto final do documento: obrigatório e com no máximo {@link #TEXTO_MAX} caracteres. */
    static String texto(String bruto, Map<String, String> valores) {
        String texto = aplicar(bruto, valores).trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("Escreva o texto do documento");
        }
        if (texto.length() > TEXTO_MAX) {
            throw new IllegalArgumentException("O texto do documento deve ter no máximo " + TEXTO_MAX + " caracteres");
        }
        return texto;
    }

    static String titulo(Atendimento.TipoDocumento tipo) {
        return tipo == Atendimento.TipoDocumento.ATESTADO ? "Atestado médico" : "Declaração de comparecimento";
    }

    /** Folha para imprimir. prof nulo (declaração da recepção): linha para assinatura e carimbo da clínica. */
    static String html(String clinica, Atendimento.Documento d, Atendimento.PacienteResumo paciente,
                       ProfissionalClinico prof) {
        String titulo = titulo(d.tipo());
        StringBuilder h = new StringBuilder(2048);
        h.append("<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\">")
                .append("<title>").append(esc(titulo)).append("</title>")
                .append("<style>body{font-family:sans-serif;margin:2.5rem;color:#111;font-size:15px;line-height:1.6}")
                .append("h1{font-size:1.3rem;margin:0}h2{font-size:1.15rem;text-align:center;margin:2.5rem 0 1.5rem;")
                .append("letter-spacing:.04em;text-transform:uppercase}.meta{margin:.4rem 0 0;color:#333}")
                .append(".texto{white-space:pre-wrap;text-align:justify;margin:0 0 1rem}")
                .append(".local{margin-top:2rem}.assinatura{margin:4rem auto 0;border-top:1px solid #111;width:20rem;")
                .append("padding-top:.3rem;text-align:center}.rodape{margin-top:2.5rem;font-size:12px;color:#555}")
                .append("@media print{body{margin:1.5cm}}</style></head><body>");
        h.append("<h1>").append(esc(clinica)).append("</h1>");
        h.append("<div class=\"meta\">Paciente: ").append(esc(paciente.nome()));
        if (paciente.cpf() != null && !paciente.cpf().isBlank()) {
            h.append(" &middot; CPF: ").append(esc(cpf(paciente.cpf())));
        }
        h.append("</div>");
        h.append("<h2>").append(esc(titulo)).append("</h2>");
        h.append("<p class=\"texto\">").append(esc(d.texto())).append("</p>");
        if (d.cid() != null && !d.cid().isBlank()) {
            h.append("<p class=\"texto\">CID-10: ").append(esc(d.cid())).append(" (informado com autorização do paciente)</p>");
        }
        h.append("<p class=\"local\">").append(d.dtEmissao().format(DATA)).append("</p>");
        h.append("<div class=\"assinatura\">");
        if (prof != null) {
            h.append(esc(prof.nome()));
            String conselho = conselho(prof);
            if (!conselho.isEmpty()) {
                h.append("<br>").append(esc(conselho));
            }
        } else {
            h.append("Assinatura e carimbo da clínica");
        }
        h.append("</div>");
        h.append("<div class=\"rodape\">Documento nº ").append(d.id()).append(" &middot; atendimento nº ")
                .append(d.nrAtendimento()).append(" &middot; emitido em ").append(d.dtEmissao().format(DATA))
                .append(" às ").append(d.dtEmissao().format(HORA)).append(" por ").append(esc(d.usuario()))
                .append(".</div></body></html>");
        return h.toString();
    }

    private static String conselho(ProfissionalClinico prof) {
        if (prof.conselhoNumero() == null || prof.conselhoNumero().isBlank()) {
            return "";
        }
        return prof.conselhoNumero() + (prof.conselhoUf() == null || prof.conselhoUf().isBlank() ? "" : "/" + prof.conselhoUf());
    }

    private static String idade(Atendimento.PacienteResumo paciente, LocalDateTime agora) {
        if (paciente.nascimento() == null) {
            return "";
        }
        int anos = Period.between(paciente.nascimento(), agora.toLocalDate()).getYears();
        return anos + (anos == 1 ? " ano" : " anos");
    }

    static String cpf(String cpf) {
        if (cpf == null) {
            return "";
        }
        String d = cpf.replaceAll("\\D", "");
        return d.length() == 11 ? d.substring(0, 3) + "." + d.substring(3, 6) + "." + d.substring(6, 9) + "-" + d.substring(9) : cpf;
    }

    private static String esc(String valor) {
        return ConsultorioService.esc(valor);
    }
}
