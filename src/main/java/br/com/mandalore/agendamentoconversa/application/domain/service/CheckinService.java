package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.CheckinUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.CheckinConfigPort;

/**
 * Check-in pelo WhatsApp: o paciente lê o QR da recepção (abre o WhatsApp com "Cheguei ABC123"), confirma CPF e data
 * de nascimento e a chegada é registrada como se fosse na recepção (ator {@link Atendimento#CHEGADA_WHATSAPP}). O
 * código muda todo dia, então só quem está na clínica consegue fazer o check-in.
 */
@Service
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class CheckinService implements CheckinUseCase {

    static final int ANTECEDENCIA_MIN = 60;
    static final int TENTATIVAS = 3;
    static final Duration ESPERA = Duration.ofMinutes(10);
    static final String ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int TAMANHO_CODIGO = 6;
    /** "Cheguei" sem código só conta como check-in em mensagem curta ("cheguei", "já cheguei na clínica"). */
    static final int MENSAGEM_CURTA = 30;

    static final String SEM_CODIGO = "Para fazer o check-in, aponte a câmera do celular para o QR code da recepção. "
            + "Ele abre esta conversa com a mensagem pronta. Se preferir, escreva *Cheguei* e o código que aparece "
            + "embaixo do QR.";
    static final String CODIGO_INVALIDO = "Esse código não vale para hoje. Confira o código embaixo do QR code da "
            + "recepção e envie de novo, por exemplo: Cheguei ABC123.";
    static final String PEDE_DADOS = "Para confirmar sua chegada, envie seu CPF e sua data de nascimento, por "
            + "exemplo: 123.456.789-01 02/01/1980";
    static final String DADOS_INVALIDOS = "Não entendi. Envie o CPF e a data de nascimento na mesma mensagem, por "
            + "exemplo: 123.456.789-01 02/01/1980";
    static final String NAO_ENCONTRADO = "Não encontrei cadastro com esse CPF e essa data de nascimento. Confira e "
            + "envie de novo.";
    static final String LIMITE = "Não consegui confirmar seus dados. Procure a recepção para registrar sua chegada.";
    static final String JA_FEITO = "Sua chegada já está registrada. Aguarde na recepção: você será chamado(a) pelo "
            + "painel.";
    static final String SEM_CONSULTA = "Não encontrei consulta sua para hoje aguardando chegada. Procure a recepção.";
    static final String CEDO = "Sua consulta é às %s. O check-in pelo WhatsApp abre %d minutos antes, a partir das %s.";
    static final String FEITO = "Check-in feito, %s! Sua chegada para a consulta das %s com %s está registrada. "
            + "Aguarde na recepção: você será chamado(a) pelo painel.";
    static final String NAO_REGISTRADO = "Não consegui registrar sua chegada agora. Procure a recepção.";

    private static final Logger LOG = LoggerFactory.getLogger(CheckinService.class);
    private static final int LIMITE_ESPERAS = 10_000;
    private static final Pattern CHEGUEI = Pattern.compile(
            "\\b(?:cheguei|check[ -]?in)\\b[^a-z0-9]*([a-z0-9]{" + TAMANHO_CODIGO + "})?(?![a-z0-9])");
    private static final Pattern CPF = Pattern.compile("(?<!\\d)(\\d{3})\\.?(\\d{3})\\.?(\\d{3})[-.]?(\\d{2})(?!\\d)");
    private static final Pattern DATA = Pattern.compile("(?<!\\d)(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})(?!\\d)");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> AGUARDANDO_CHEGADA = Set.of("MARCADO", "CONFIRMADO");
    private static final Set<String> JA_CHEGOU = Set.of("AGUARDANDO", "EM_CONSULTA");

    private final AtendimentoUseCase atendimento;
    private final AtendimentoPort atendimentos;
    private final HabilitacaoAtendimento habilitacao;
    private final CheckinConfigPort config;
    private final Clock relogio;
    /** Conversas que enviaram o código do dia e ainda devem o CPF e a data de nascimento. */
    private final Map<String, Espera> esperas = new ConcurrentHashMap<>();

    private record Espera(int tentativas, Instant expira) {
    }

    record Dados(String cpf, LocalDate nascimento) {
    }

    @Autowired
    public CheckinService(AtendimentoUseCase atendimento, AtendimentoPort atendimentos,
                          HabilitacaoAtendimento habilitacao, CheckinConfigPort config) {
        this(atendimento, atendimentos, habilitacao, config, Clock.system(AtendimentoService.ZONA));
    }

    CheckinService(AtendimentoUseCase atendimento, AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao,
                   CheckinConfigPort config, Clock relogio) {
        this.atendimento = atendimento;
        this.atendimentos = atendimentos;
        this.habilitacao = habilitacao;
        this.config = config;
        this.relogio = relogio;
    }

    @Override
    public Optional<Atendimento.CheckinDoDia> doDia(String conta) {
        if (!habilitacao.habilitada(conta)) {
            return Optional.empty();
        }
        Optional<String> numero = config.whatsapp(conta);
        Optional<String> segredo = config.segredo(conta);
        if (numero.isEmpty() || segredo.isEmpty()) {
            return Optional.empty();
        }
        LocalDate hoje = LocalDate.now(relogio);
        String codigo = codigo(segredo.get(), conta, hoje);
        String texto = URLEncoder.encode("Cheguei " + codigo, StandardCharsets.UTF_8).replace("+", "%20");
        return Optional.of(new Atendimento.CheckinDoDia(hoje, codigo, "https://wa.me/" + numero.get() + "?text=" + texto));
    }

    @Override
    public Optional<String> responder(String conta, String conversa, String texto) {
        if (texto == null || texto.isBlank() || !habilitacao.habilitada(conta)) {
            return Optional.empty();
        }
        Optional<String> segredo = config.segredo(conta);
        if (segredo.isEmpty()) {
            return Optional.empty();
        }
        String chave = conta + ":" + conversa;
        Instant agora = relogio.instant();
        String normal = Textos.normalizar(texto);
        Matcher cheguei = CHEGUEI.matcher(normal);
        if (cheguei.find()) {
            String codigo = cheguei.group(1);
            if (codigo == null) {
                return normal.length() <= MENSAGEM_CURTA ? Optional.of(SEM_CODIGO) : Optional.empty();
            }
            if (!codigo(segredo.get(), conta, LocalDate.now(relogio)).equalsIgnoreCase(codigo)) {
                esperas.remove(chave);
                return Optional.of(CODIGO_INVALIDO);
            }
            esperar(chave, new Espera(0, agora.plus(ESPERA)));
            return Optional.of(PEDE_DADOS);
        }
        Espera espera = esperas.get(chave);
        if (espera == null) {
            return Optional.empty();
        }
        if (espera.expira().isBefore(agora) || !normal.matches(".*\\d.*")) {
            esperas.remove(chave);
            return Optional.empty();
        }
        Optional<Dados> dados = dados(texto);
        if (dados.isEmpty()) {
            return Optional.of(tentativa(chave, espera, DADOS_INVALIDOS));
        }
        Optional<Atendimento.PacienteResumo> paciente = atendimentos.pacientesPorCpf(conta, dados.get().cpf()).stream()
                .filter(p -> dados.get().nascimento().equals(p.nascimento()))
                .findFirst();
        if (paciente.isEmpty()) {
            return Optional.of(tentativa(chave, espera, NAO_ENCONTRADO));
        }
        esperas.remove(chave);
        return Optional.of(registrar(conta, paciente.get()));
    }

    private String registrar(String conta, Atendimento.PacienteResumo paciente) {
        LocalDateTime agora = LocalDateTime.now(relogio);
        LocalDate hoje = agora.toLocalDate();
        List<Atendimento.VisitaPaciente> doDia = atendimentos.visitasDoPaciente(conta, paciente.id(), false).stream()
                .filter(v -> v.inicio() != null && v.inicio().toLocalDate().equals(hoje))
                .sorted(Comparator.comparing(Atendimento.VisitaPaciente::inicio))
                .toList();
        if (doDia.stream().anyMatch(v -> JA_CHEGOU.contains(v.statusAgendamento()))) {
            return JA_FEITO;
        }
        List<Atendimento.VisitaPaciente> pendentes = doDia.stream()
                .filter(v -> AGUARDANDO_CHEGADA.contains(v.statusAgendamento()))
                .toList();
        if (pendentes.isEmpty()) {
            return SEM_CONSULTA;
        }
        Optional<Atendimento.VisitaPaciente> liberada = pendentes.stream()
                .filter(v -> !v.inicio().minusMinutes(ANTECEDENCIA_MIN).isAfter(agora))
                .reduce((anterior, seguinte) -> seguinte);
        if (liberada.isEmpty()) {
            LocalDateTime inicio = pendentes.get(0).inicio();
            return CEDO.formatted(inicio.format(HORA), ANTECEDENCIA_MIN,
                    inicio.minusMinutes(ANTECEDENCIA_MIN).format(HORA));
        }
        Atendimento.VisitaPaciente visita = liberada.get();
        Atendimento.Operacao op = atendimento.registrarChegada(conta, visita.agendamentoId(),
                Atendimento.CHEGADA_WHATSAPP);
        if (op == null || op.codigo() != CodigoNegocio.SUCESSO) {
            LOG.info("checkin_whatsapp agendamento_id={} codigo={}", visita.agendamentoId(),
                    op == null ? null : op.codigo());
            return NAO_REGISTRADO;
        }
        LOG.info("checkin_whatsapp agendamento_id={} codigo=sucesso", visita.agendamentoId());
        return FEITO.formatted(primeiroNome(paciente.nome()), visita.inicio().format(HORA), visita.profissional());
    }

    private String tentativa(String chave, Espera espera, String resposta) {
        int tentativas = espera.tentativas() + 1;
        if (tentativas >= TENTATIVAS) {
            esperas.remove(chave);
            return LIMITE;
        }
        esperar(chave, new Espera(tentativas, espera.expira()));
        return resposta;
    }

    private void esperar(String chave, Espera espera) {
        if (esperas.size() >= LIMITE_ESPERAS) {
            esperas.clear();
        }
        esperas.put(chave, espera);
    }

    static Optional<Dados> dados(String texto) {
        Matcher cpf = CPF.matcher(texto);
        if (!cpf.find()) {
            return Optional.empty();
        }
        String digitos = cpf.group(1) + cpf.group(2) + cpf.group(3) + cpf.group(4);
        String resto = texto.substring(0, cpf.start()) + " " + texto.substring(cpf.end());
        Matcher data = DATA.matcher(resto);
        if (!data.find()) {
            return Optional.empty();
        }
        int ano = Integer.parseInt(data.group(3));
        if (data.group(3).length() == 2) {
            int atual = LocalDate.now(AtendimentoService.ZONA).getYear() % 100;
            ano += ano > atual ? 1900 : 2000;
        }
        try {
            return Optional.of(new Dados(digitos,
                    LocalDate.of(ano, Integer.parseInt(data.group(2)), Integer.parseInt(data.group(1)))));
        } catch (java.time.DateTimeException ex) {
            return Optional.empty();
        }
    }

    /** HMAC do dia com o segredo da clínica, em {@value #TAMANHO_CODIGO} letras e dígitos sem ambiguidade. */
    static String codigo(String segredo, String conta, LocalDate dia) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(("checkin:" + conta + ":" + dia).getBytes(StandardCharsets.UTF_8));
            StringBuilder codigo = new StringBuilder(TAMANHO_CODIGO);
            for (int i = 0; i < TAMANHO_CODIGO; i++) {
                codigo.append(ALFABETO.charAt((hash[i] & 0xff) % ALFABETO.length()));
            }
            return codigo.toString();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 indisponível", ex);
        }
    }

    private static String primeiroNome(String nome) {
        if (nome == null || nome.isBlank()) {
            return "tudo certo";
        }
        String primeiro = nome.strip().split("\\s+")[0].toLowerCase(Locale.ROOT);
        return Character.toUpperCase(primeiro.charAt(0)) + primeiro.substring(1);
    }
}
