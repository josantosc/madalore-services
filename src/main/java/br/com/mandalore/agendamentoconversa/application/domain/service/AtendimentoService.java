package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AtendimentoPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AvisoFilaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.LocaisChamadaPort;

/** Recepção: fila do dia, chegada, chamada, falta e encaixe. Não lida com conteúdo clínico. */
@Service
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AtendimentoService implements AtendimentoUseCase {

    static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    static final int MOTIVO_MAX = 300;
    static final int ENCAIXE_DURACAO_MIN = 15;
    static final int BUSCA_LIMITE = 10;
    static final int PAINEL_LIMITE = 6;
    static final int LOCAL_MAX = 60;
    static final String ACAO_FICHA = "FICHA_PACIENTE";
    private static final Set<String> PARTICULAS = Set.of("da", "de", "do", "das", "dos", "e");
    private static final int NOME_MAX = 240;
    private static final int TELEFONE_MAX = 40;

    private final AtendimentoPort atendimentos;
    private final Clock relogio;
    private final HabilitacaoAtendimento habilitacao;
    private final AvisoFilaPort aviso;
    private final LocaisChamadaPort locais;

    @Autowired
    public AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, AvisoFilaPort aviso,
                              LocaisChamadaPort locais) {
        this(atendimentos, habilitacao, Clock.system(ZONA), aviso, locais);
    }

    AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio) {
        this(atendimentos, habilitacao, relogio, AvisoFilaPort.NENHUM);
    }

    AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio,
                       AvisoFilaPort aviso) {
        this(atendimentos, habilitacao, relogio, aviso, LocaisChamadaPort.NENHUM);
    }

    AtendimentoService(AtendimentoPort atendimentos, HabilitacaoAtendimento habilitacao, Clock relogio,
                       AvisoFilaPort aviso, LocaisChamadaPort locais) {
        this.atendimentos = atendimentos;
        this.habilitacao = habilitacao;
        this.relogio = relogio;
        this.aviso = aviso;
        this.locais = locais;
    }

    @Override
    public List<Atendimento.FilaItem> fila(String conta, LocalDate data) {
        exigirHabilitada(conta);
        return atendimentos.fila(conta, data == null ? LocalDate.now(relogio) : data);
    }

    @Override
    public List<Atendimento.Chamada> chamadasDoPainel(String conta) {
        exigirHabilitada(conta);
        return atendimentos.chamadas(conta, LocalDate.now(relogio), PAINEL_LIMITE).stream()
                .map(c -> new Atendimento.Chamada(nomePublico(c.paciente()), c.profissional(), c.local(), c.chamadoEm()))
                .toList();
    }

    @Override
    public List<String> locaisDeChamada(String conta) {
        exigirHabilitada(conta);
        return locais.locais(conta);
    }

    /** Local da chamada sem espaços sobrando; vazio vira null (o painel mostra o profissional). */
    static String localChamada(String local) {
        if (local == null || local.isBlank()) {
            return null;
        }
        String limpo = local.strip().replaceAll("\\s+", " ");
        if (limpo.length() > LOCAL_MAX) {
            throw new IllegalArgumentException("O local da chamada aceita até " + LOCAL_MAX + " caracteres");
        }
        return limpo;
    }

    /** "MARIA DA SILVA OLIVEIRA" vira "Maria S. O.": primeiro nome e iniciais, sem partículas (da, de, dos...). */
    static String nomePublico(String nome) {
        if (nome == null || nome.isBlank()) {
            return "";
        }
        String[] partes = nome.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder s = new StringBuilder(Character.toUpperCase(partes[0].charAt(0)) + partes[0].substring(1));
        for (int i = 1; i < partes.length; i++) {
            if (!PARTICULAS.contains(partes[i])) {
                s.append(' ').append(Character.toUpperCase(partes[i].charAt(0))).append('.');
            }
        }
        return s.toString();
    }

    @Override
    public Atendimento.Operacao registrarChegada(String conta, long agendamentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        Atendimento.Operacao op = atendimentos.registrarChegada(conta, agendamentoId, ator);
        return avisar(conta, op, "CHEGADA", agendamentoId, op == null ? null : op.id());
    }

    @Override
    public Atendimento.Operacao desfazerChegada(String conta, long atendimentoId, String motivo, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        String texto = motivo == null ? "" : motivo.trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("Informe o motivo para desfazer a chegada");
        }
        if (texto.length() > MOTIVO_MAX) {
            throw new IllegalArgumentException("O motivo deve ter no máximo " + MOTIVO_MAX + " caracteres");
        }
        return avisar(conta, atendimentos.desfazerChegada(conta, atendimentoId, texto, ator), "CHEGADA_DESFEITA",
                null, atendimentoId);
    }

    @Override
    public List<Atendimento.Evento> eventos(String conta, long atendimentoId) {
        exigirHabilitada(conta);
        return atendimentos.eventos(conta, atendimentoId);
    }

    @Override
    public Atendimento.Operacao chamar(String conta, long atendimentoId, String local, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        String limpo = localChamada(local);
        return avisar(conta, atendimentos.chamar(conta, atendimentoId, null, limpo, ator), "CHAMADO", null, atendimentoId);
    }

    @Override
    public Atendimento.Operacao marcarFalta(String conta, long agendamentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        return avisar(conta, atendimentos.marcarFalta(conta, agendamentoId, ator), "FALTOU", agendamentoId, null);
    }

    @Override
    public Atendimento.Operacao desfazerFalta(String conta, long agendamentoId, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        return avisar(conta, atendimentos.desfazerFalta(conta, agendamentoId, ator), "FALTA_DESFEITA", agendamentoId,
                null);
    }

    @Override
    public Atendimento.Operacao encaixe(String conta, Atendimento.Encaixe encaixe, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        if (encaixe == null) {
            throw new IllegalArgumentException("Informe os dados do encaixe");
        }
        if (encaixe.profissionalId() <= 0 || encaixe.especialidadeId() <= 0) {
            throw new IllegalArgumentException("Escolha o profissional e a especialidade");
        }
        String cpf = AgendaService.cpf(encaixe.cpf());
        if (encaixe.nascimento() == null) {
            throw new IllegalArgumentException("Informe a data de nascimento");
        }
        if (encaixe.nascimento().isAfter(LocalDate.now(relogio))) {
            throw new IllegalArgumentException("A data de nascimento não pode ser futura");
        }
        String nome = encaixe.nome() == null ? "" : encaixe.nome().trim();
        if (nome.isEmpty()) {
            throw new IllegalArgumentException("Informe o nome do paciente");
        }
        if (nome.length() > NOME_MAX) {
            throw new IllegalArgumentException("O nome deve ter no máximo " + NOME_MAX + " caracteres");
        }
        String telefone = encaixe.telefone() == null ? "" : encaixe.telefone().trim();
        if (telefone.length() > TELEFONE_MAX) {
            throw new IllegalArgumentException("O telefone deve ter no máximo " + TELEFONE_MAX + " caracteres");
        }
        Atendimento.Encaixe limpo = new Atendimento.Encaixe(encaixe.profissionalId(), encaixe.especialidadeId(),
                encaixe.convenioId(), cpf, encaixe.nascimento(), nome, telefone);
        Atendimento.Operacao op = atendimentos.encaixe(conta, limpo, ENCAIXE_DURACAO_MIN, ator);
        return avisar(conta, op, "ENCAIXE", null, op == null ? null : op.id());
    }

    @Override
    public Atendimento.CadastrosEncaixe cadastrosEncaixe(String conta) {
        exigirHabilitada(conta);
        return atendimentos.cadastrosEncaixe(conta);
    }

    @Override
    public List<Atendimento.PacienteResumo> buscarPacientes(String conta, String termo) {
        exigirHabilitada(conta);
        String texto = termo == null ? "" : termo.trim();
        if (texto.length() < 2) {
            return List.of();
        }
        return atendimentos.buscarPacientes(conta, texto, BUSCA_LIMITE);
    }

    @Override
    public List<Atendimento.PacienteResumo> pacientesPorCpf(String conta, String cpf) {
        exigirHabilitada(conta);
        return atendimentos.pacientesPorCpf(conta, cpfInformado(cpf));
    }

    @Override
    public Atendimento.FichaPaciente fichaDoPaciente(String conta, long pacienteId, String ator, String ip) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        Atendimento.PacienteResumo paciente = atendimentos.paciente(conta, pacienteId)
                .orElseThrow(() -> new IllegalArgumentException("Paciente não encontrado"));
        List<Atendimento.VisitaPaciente> visitas = atendimentos.visitasDoPaciente(conta, pacienteId, false);
        atendimentos.registrarAcesso(conta, null, pacienteId, ACAO_FICHA, ator, ip);
        return new Atendimento.FichaPaciente(paciente, visitas);
    }

    @Override
    public Atendimento.Operacao emitirDeclaracao(String conta, long atendimentoId, String texto, String ator) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        Optional<Atendimento.Ficha> ficha = atendimentos.atendimento(conta, atendimentoId);
        if (ficha.isEmpty()) {
            return Atendimento.Operacao.falha(CodigoNegocio.NAO_ENCONTRADO);
        }
        Atendimento.Ficha f = ficha.get();
        if (f.status() == Atendimento.Status.CANCELADO) {
            return Atendimento.Operacao.falha(CodigoNegocio.STATUS_INVALIDO_PARA_DOCUMENTO);
        }
        Atendimento.PacienteResumo paciente = atendimentos.paciente(conta, f.pacienteId())
                .orElseThrow(() -> new IllegalArgumentException("Paciente não encontrado"));
        LocalDateTime agora = LocalDateTime.now(relogio);
        String clinica = clinica(conta, atendimentoId);
        String declaracao = Documentos.texto(texto, Documentos.marcadores(clinica, f, paciente, null, null, null, agora));
        return atendimentos.emitirDocumento(conta, new Atendimento.Documento(null, atendimentoId, f.pacienteId(), null,
                Atendimento.TipoDocumento.DECLARACAO, declaracao, null, null, agora, ator));
    }

    @Override
    public String impressaoDeclaracao(String conta, long documentoId, String ator, String ip) {
        exigirHabilitada(conta);
        exigirAtor(ator);
        Atendimento.Documento documento = atendimentos.documento(conta, documentoId)
                .filter(d -> d.tipo() == Atendimento.TipoDocumento.DECLARACAO)
                .orElseThrow(() -> new IllegalArgumentException("Documento não encontrado"));
        Atendimento.PacienteResumo paciente = atendimentos.paciente(conta, documento.pacienteId())
                .orElseThrow(() -> new IllegalArgumentException("Paciente não encontrado"));
        atendimentos.registrarAcesso(conta, documento.nrAtendimento(), documento.pacienteId(), Documentos.ACAO_IMPRESSAO,
                ator, ip);
        return Documentos.html(clinica(conta, documento.nrAtendimento()), documento, paciente, null);
    }

    private String clinica(String conta, long atendimentoId) {
        return atendimentos.cabecalhoAtendimento(conta, atendimentoId).map(AtendimentoPort.CabecalhoReceita::clinica)
                .orElse("");
    }

    /** Só os 11 dígitos; pontos, traço e espaços são ignorados. */
    public static String cpfInformado(String cpf) {
        String digitos = cpf == null ? "" : cpf.replaceAll("[.\\-\\s]", "");
        if (!digitos.matches("\\d{11}")) {
            throw new IllegalArgumentException("Informe o CPF com 11 dígitos");
        }
        return digitos;
    }

    /** O aviso é só para as telas recarregarem; falhar nele não desfaz a operação já gravada. */
    private Atendimento.Operacao avisar(String conta, Atendimento.Operacao op, String tipo, Long agendamentoId,
                                        Long atendimentoId) {
        if (op != null && op.codigo() == CodigoNegocio.SUCESSO) {
            try {
                aviso.publicar(conta, new AvisoFilaPort.Aviso(tipo, agendamentoId, atendimentoId));
            } catch (RuntimeException ignorado) {
                // as telas também recarregam sozinhas a cada minuto
            }
        }
        return op;
    }

    /** Fluxo desligado ou conta fora da agenda do Mandalore: AtendimentoDesabilitadoException (HTTP 404). */
    private void exigirHabilitada(String conta) {
        habilitacao.exigir(conta);
    }

    private static void exigirAtor(String ator) {
        if (ator == null || ator.isBlank()) {
            throw new IllegalArgumentException("Usuário não identificado");
        }
    }
}
