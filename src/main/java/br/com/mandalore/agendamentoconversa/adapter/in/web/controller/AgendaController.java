package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcesso;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.Modulo;
import br.com.mandalore.agendamentoconversa.application.domain.model.Agenda;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AgendaUseCase;

/**
 * API da tela da recepção (/agenda). O parâmetro {@code conta} escolhe a clínica; o login da recepção de uma clínica
 * só enxerga a própria conta.
 */
@RestController
@ConditionalOnProperty(name = {"agenda.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AgendaController {

    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private final AgendaUseCase agenda;
    private final String contaPadrao;

    public AgendaController(AgendaUseCase agenda, @Value("${agenda.conta-padrao:1}") String contaPadrao) {
        this.agenda = agenda;
        this.contaPadrao = contaPadrao == null || contaPadrao.isBlank() ? "1" : contaPadrao.trim();
    }

    public record Escolha(boolean jaFeitoNoSistema) {
    }

    public record RemarcarRequest(long profissionalId, long especialidadeId, Long convenioId, LocalDateTime inicio,
                                  int duracaoMin, String local, String codigoExterno, boolean jaFeitoNoSistema) {
    }

    public record OcuparRequest(long profissionalId, LocalDateTime inicio, LocalDateTime fim) {
    }

    public record BloqueioRequest(Long profissionalId, LocalDateTime inicio, LocalDateTime fim, String motivo) {
    }

    public record ConcluirRequest(String codigoExterno, String observacao) {
    }

    public record ConferirRequest(Agenda.SituacaoConferencia situacao, String observacao) {
    }

    static final class Proibido extends RuntimeException {
        Proibido() {
            super("Seu usuário não tem acesso à agenda desta clínica");
        }
    }

    @GetMapping("/agenda")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("agenda/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/agenda/api/contexto", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> contexto(@RequestParam(required = false) String conta, @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        String escolhida = conta(conta, login);
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("conta", escolhida);
        corpo.put("contas", contasVisiveis(login));
        corpo.put("hoje", LocalDate.now(ZONA).toString());
        corpo.put("resumo", agenda.resumo(escolhida));
        corpo.put("cadastros", agenda.cadastros(escolhida));
        corpo.put("podeCadastrar", login.modulos().contains(Modulo.AGENDA_CADASTROS));
        return corpo;
    }

    @GetMapping(value = "/agenda/api/dia", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgendaUseCase.Dia dia(
            @RequestParam(required = false) String conta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login
    ) {
        return agenda.dia(conta(conta, login), data == null ? LocalDate.now(ZONA) : data);
    }

    @GetMapping(value = "/agenda/api/agendamentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgendaUseCase.Detalhe detalhe(@PathVariable long id, @RequestParam(required = false) String conta,
                                         @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.detalhe(conta(conta, login), id);
    }

    @PostMapping(value = "/agenda/api/agendamentos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> marcar(@RequestParam(required = false) String conta, @RequestBody AgendaUseCase.NovaConsulta consulta,
                                                      @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(agenda.marcar(conta(conta, login), consulta, login.usuario()));
    }

    @PostMapping(value = "/agenda/api/agendamentos/{id}/confirmar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> confirmar(@PathVariable long id, @RequestParam(required = false) String conta,
                                                         @RequestBody Escolha escolha, @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(agenda.confirmar(conta(conta, login), id, escolha.jaFeitoNoSistema(), login.usuario()));
    }

    @PostMapping(value = "/agenda/api/agendamentos/{id}/cancelar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> cancelar(@PathVariable long id, @RequestParam(required = false) String conta,
                                                        @RequestBody Escolha escolha, @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(agenda.cancelar(conta(conta, login), id, escolha.jaFeitoNoSistema(), login.usuario()));
    }

    @PostMapping(value = "/agenda/api/agendamentos/{id}/remarcar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> remarcar(@PathVariable long id, @RequestParam(required = false) String conta,
                                                        @RequestBody RemarcarRequest request, @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        AgendaUseCase.NovoHorario novo = new AgendaUseCase.NovoHorario(request.profissionalId(), request.especialidadeId(),
                request.convenioId(), request.inicio(), request.duracaoMin(), request.local(), request.codigoExterno());
        return operacao(agenda.remarcar(conta(conta, login), id, novo, request.jaFeitoNoSistema(), login.usuario()));
    }

    @PostMapping(value = "/agenda/api/ocupar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Agenda.Bloqueio ocupar(@RequestParam(required = false) String conta, @RequestBody OcuparRequest request,
                                  @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.ocupar(conta(conta, login), request.profissionalId(), request.inicio(), request.fim(), login.usuario());
    }

    @GetMapping(value = "/agenda/api/bloqueios", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Agenda.Bloqueio> bloqueios(
            @RequestParam(required = false) String conta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate de,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate ate,
            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login
    ) {
        LocalDate inicio = de == null ? LocalDate.now(ZONA) : de;
        return agenda.bloqueios(conta(conta, login), inicio, ate == null ? inicio.plusDays(60) : ate);
    }

    @PostMapping(value = "/agenda/api/bloqueios", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Agenda.Bloqueio bloquear(@RequestParam(required = false) String conta, @RequestBody BloqueioRequest request,
                                    @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.bloquear(conta(conta, login),
                new Agenda.Bloqueio(null, request.profissionalId(), request.inicio(), request.fim(), request.motivo(), login.usuario()),
                login.usuario());
    }

    @DeleteMapping("/agenda/api/bloqueios/{id}")
    public ResponseEntity<Void> desbloquear(@PathVariable long id, @RequestParam(required = false) String conta,
                                            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.desbloquear(conta(conta, login), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/agenda/cadastros")
    public ResponseEntity<byte[]> paginaCadastros() throws IOException {
        try (InputStream html = new ClassPathResource("agenda/cadastros.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/agenda/cadastros/api/contexto", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> contextoCadastros(@RequestParam(required = false) String conta,
                                                 @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        String escolhida = conta(conta, login);
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("conta", escolhida);
        corpo.put("contas", contasVisiveis(login));
        corpo.put("cadastros", agenda.cadastros(escolhida));
        corpo.put("grades", agenda.grades(escolhida));
        corpo.put("especialidadesDosProfissionais", agenda.especialidadesDosProfissionais(escolhida));
        corpo.put("credenciais", agenda.credenciais(escolhida));
        corpo.put("usuarios", agenda.usuariosVinculaveis(escolhida));
        corpo.put("agenda", login.modulos().contains(Modulo.AGENDA));
        return corpo;
    }

    @PutMapping(value = "/agenda/cadastros/api/profissional/{id}/especialidades", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> vincularEspecialidades(@PathVariable long id, @RequestParam(required = false) String conta,
                                                       @RequestBody List<Long> especialidades,
                                                       @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        agenda.vincularEspecialidades(conta(conta, login), id, especialidades);
        return ResponseEntity.noContent().build();
    }

    @PutMapping(value = "/agenda/cadastros/api/profissional/{id}/credencial", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Agenda.Credencial salvarCredencial(@PathVariable long id, @RequestParam(required = false) String conta,
                                              @RequestBody Agenda.Credencial credencial,
                                              @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.salvarCredencial(conta(conta, login), id, credencial);
    }

    @PutMapping(value = "/agenda/cadastros/api/grades", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Agenda.Grade salvarGrade(@RequestParam(required = false) String conta, @RequestBody Agenda.Grade grade,
                                    @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.salvarGrade(conta(conta, login), grade);
    }

    @DeleteMapping("/agenda/cadastros/api/grades/{id}")
    public ResponseEntity<Void> removerGrade(@PathVariable long id, @RequestParam(required = false) String conta,
                                             @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.removerGrade(conta(conta, login), id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping(value = "/agenda/cadastros/api/{tipo}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Agenda.Item salvarItem(@PathVariable String tipo, @RequestParam(required = false) String conta, @RequestBody Agenda.Item item,
                                  @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.salvarItem(conta(conta, login), cadastro(tipo), item);
    }

    @DeleteMapping("/agenda/cadastros/api/{tipo}/{id}")
    public ResponseEntity<Map<String, Object>> excluirItem(@PathVariable String tipo, @PathVariable long id,
                                                           @RequestParam(required = false) String conta,
                                                           @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        Agenda.Cadastro cadastro = cadastro(tipo);
        Agenda.Exclusao exclusao = agenda.excluirItem(conta(conta, login), cadastro, id);
        String mensagem = switch (exclusao) {
            case EXCLUIDO -> "Excluído.";
            case DESATIVADO -> "Já existem consultas com este " + cadastro.titulo()
                    + ", então ele foi desativado (o histórico continua) e o bot deixa de oferecê-lo.";
            case NAO_ENCONTRADO -> "Cadastro não encontrado. Atualize a tela.";
        };
        Map<String, Object> corpo = Map.of("resultado", exclusao.name(), "mensagem", mensagem);
        return exclusao == Agenda.Exclusao.NAO_ENCONTRADO
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(corpo)
                : ResponseEntity.ok(corpo);
    }

    private static Agenda.Cadastro cadastro(String tipo) {
        try {
            return Agenda.Cadastro.valueOf(tipo.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Cadastro inválido: use profissional, especialidade ou convenio");
        }
    }

    @GetMapping(value = "/agenda/api/pacientes", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Agenda.Paciente> pacientes(@RequestParam(required = false) String conta, @RequestParam(defaultValue = "") String termo,
                                           @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.buscarPacientes(conta(conta, login), termo);
    }

    @GetMapping(value = "/agenda/api/pacientes/historico", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgendaUseCase.HistoricoPaciente historico(
            @RequestParam(required = false) String conta,
            @RequestParam String cpf,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nascimento,
            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login
    ) {
        return agenda.historicoPaciente(conta(conta, login), cpf, nascimento);
    }

    @GetMapping(value = "/agenda/api/pendencias", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> pendencias(@RequestParam(required = false) String conta,
                                          @RequestParam(required = false) Agenda.SituacaoPendencia situacao,
                                          @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        String escolhida = conta(conta, login);
        LocalDateTime agora = LocalDateTime.now(ZONA);
        List<Map<String, Object>> itens = agenda.pendencias(escolhida, situacao).stream().map(p -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("pendencia", p);
            item.put("atrasada", p.atrasada(agora));
            return item;
        }).toList();
        return Map.of("itens", itens, "resumo", agenda.resumo(escolhida));
    }

    @PostMapping(value = "/agenda/api/pendencias/{id}/concluir", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> concluir(@PathVariable long id, @RequestParam(required = false) String conta, @RequestBody ConcluirRequest request,
                                         @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.concluirPendencia(conta(conta, login), id, request.codigoExterno(), request.observacao(), login.usuario())
                ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @PostMapping("/agenda/api/pendencias/{id}/reabrir")
    public ResponseEntity<Void> reabrir(@PathVariable long id, @RequestParam(required = false) String conta,
                                        @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return agenda.reabrirPendencia(conta(conta, login), id, login.usuario())
                ? ResponseEntity.noContent().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    @GetMapping(value = "/agenda/api/conferencia", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> conferencia(
            @RequestParam(required = false) String conta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login
    ) {
        LocalDate dia = data == null ? LocalDate.now(ZONA).plusDays(1) : data;
        return Map.of("dia", dia.toString(), "agendamentos", agenda.conferencia(conta(conta, login), dia));
    }

    @PostMapping(value = "/agenda/api/conferencia/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> conferir(@PathVariable long id, @RequestParam(required = false) String conta, @RequestBody ConferirRequest request,
                                         @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        if (request.situacao() == null) {
            throw new IllegalArgumentException("Informe se está igual ou diferente no sistema da clínica");
        }
        agenda.conferir(conta(conta, login), id, request.situacao(), request.observacao(), login.usuario());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalido(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(Proibido.class)
    public ResponseEntity<Map<String, Object>> proibido(Proibido ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ex.getMessage()));
    }

    static ResponseEntity<Map<String, Object>> operacao(Agenda.Operacao operacao) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("codigo", operacao.codigo().name());
        corpo.put("agendamentoId", operacao.agendamentoId());
        corpo.put("pendencias", operacao.pendencias());
        if (operacao.codigo() == CodigoNegocio.SUCESSO) {
            return ResponseEntity.ok(corpo);
        }
        corpo.put("erro", switch (operacao.codigo()) {
            case HORARIO_INDISPONIVEL -> "Esse profissional já tem consulta nesse horário.";
            case AGENDAMENTO_NAO_ENCONTRADO -> "Consulta não encontrada ou já cancelada. Atualize a tela.";
            case AGENDAMENTO_EM_ATENDIMENTO -> "O paciente já chegou: não dá para confirmar, cancelar nem remarcar. Atualize a tela.";
            default -> "Não foi possível gravar (" + operacao.codigo().name() + ").";
        });
        return ResponseEntity.status(HttpStatus.CONFLICT).body(corpo);
    }

    /** Sem conta escolhida: a única clínica do login ou a conta padrão. */
    String conta(String pedida, AdminAcesso.Login login) {
        String conta = pedida == null || pedida.isBlank()
                ? login.contas().isEmpty() ? contaPadrao : new TreeSet<>(login.contas()).first()
                : pedida.trim();
        if (!login.podeVer(conta)) {
            throw new Proibido();
        }
        return conta;
    }

    private List<String> contasVisiveis(AdminAcesso.Login login) {
        if (!login.contas().isEmpty()) {
            return List.copyOf(new TreeSet<>(login.contas()));
        }
        TreeSet<String> contas = new TreeSet<>(agenda.contas());
        contas.add(contaPadrao);
        return List.copyOf(contas);
    }
}
