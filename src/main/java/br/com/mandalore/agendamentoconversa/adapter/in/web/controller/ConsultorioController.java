package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import br.com.mandalore.agendamentoconversa.application.domain.exception.AcessoNegadoException;
import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ConsultorioUseCase.Contexto;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Tela do médico (/consultorio) e a API. O service confere a posse do atendimento pelo profissional vinculado ao
 * usuário. Não registra em log corpo de requisição nem conteúdo clínico (LGPD).
 */
@RestController
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class ConsultorioController {

    private final ConsultorioUseCase consultorio;
    private final String contaPadrao;

    public ConsultorioController(ConsultorioUseCase consultorio, @Value("${agenda.conta-padrao:1}") String contaPadrao) {
        this.consultorio = consultorio;
        this.contaPadrao = contaPadrao == null || contaPadrao.isBlank() ? "1" : contaPadrao.trim();
    }

    public record JustificativaRequest(String justificativa) {
    }

    static final class Proibido extends RuntimeException {
        Proibido() {
            super("Seu usuário não tem acesso ao consultório desta clínica");
        }
    }

    @GetMapping("/consultorio")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("consultorio/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/consultorio/api/fila", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Atendimento.FilaItem> fila(
            @RequestParam(required = false) String conta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login
    ) {
        return consultorio.fila(contexto(conta, login), data);
    }

    @PostMapping("/consultorio/api/atendimentos/{id}/iniciar")
    public ResponseEntity<Map<String, Object>> iniciar(@PathVariable long id, @RequestParam(required = false) String conta,
                                                       @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.iniciarConsulta(contexto(conta, login), id));
    }

    @GetMapping(value = "/consultorio/api/atendimentos/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Atendimento.Prontuario> prontuario(@PathVariable long id, @RequestParam(required = false) String conta,
                                                             @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                                             HttpServletRequest request) {
        return semCache(consultorio.prontuario(contexto(conta, login), id, AdminAcesso.ip(request)));
    }

    @PutMapping(value = "/consultorio/api/atendimentos/{id}/anamnese", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> salvarAnamnese(@PathVariable long id, @RequestParam(required = false) String conta,
                                                              @RequestBody ConsultorioUseCase.AnamneseInBound anamnese,
                                                              @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.salvarRascunhoAnamnese(contexto(conta, login), id, anamnese));
    }

    @PostMapping("/consultorio/api/atendimentos/{id}/anamnese/liberar")
    public ResponseEntity<Map<String, Object>> liberarAnamnese(@PathVariable long id, @RequestParam(required = false) String conta,
                                                               @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.liberarAnamnese(contexto(conta, login), id));
    }

    @PostMapping(value = "/consultorio/api/anamneses/{id}/inativar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> inativarAnamnese(@PathVariable long id, @RequestParam(required = false) String conta,
                                                                @RequestBody JustificativaRequest request,
                                                                @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.inativarAnamnese(contexto(conta, login), id, request.justificativa()));
    }

    @PostMapping(value = "/consultorio/api/atendimentos/{id}/evolucoes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> criarEvolucao(@PathVariable long id, @RequestParam(required = false) String conta,
                                                             @RequestBody ConsultorioUseCase.EvolucaoInBound evolucao,
                                                             @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.criarEvolucao(contexto(conta, login), id, evolucao));
    }

    @PutMapping(value = "/consultorio/api/evolucoes/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> editarEvolucao(@PathVariable long id, @RequestParam(required = false) String conta,
                                                              @RequestBody ConsultorioUseCase.EvolucaoInBound evolucao,
                                                              @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.editarEvolucao(contexto(conta, login), id, evolucao));
    }

    @PostMapping("/consultorio/api/evolucoes/{id}/liberar")
    public ResponseEntity<Map<String, Object>> liberarEvolucao(@PathVariable long id, @RequestParam(required = false) String conta,
                                                               @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.liberarEvolucao(contexto(conta, login), id));
    }

    @PostMapping(value = "/consultorio/api/evolucoes/{id}/inativar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> inativarEvolucao(@PathVariable long id, @RequestParam(required = false) String conta,
                                                                @RequestBody JustificativaRequest request,
                                                                @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.inativarEvolucao(contexto(conta, login), id, request.justificativa()));
    }

    @PostMapping(value = "/consultorio/api/atendimentos/{id}/diagnosticos", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> incluirDiagnostico(@PathVariable long id, @RequestParam(required = false) String conta,
                                                                  @RequestBody ConsultorioUseCase.DiagnosticoInBound diagnostico,
                                                                  @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.incluirDiagnostico(contexto(conta, login), id, diagnostico));
    }

    @PostMapping(value = "/consultorio/api/diagnosticos/{id}/inativar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> inativarDiagnostico(@PathVariable long id, @RequestParam(required = false) String conta,
                                                                   @RequestBody JustificativaRequest request,
                                                                   @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.inativarDiagnostico(contexto(conta, login), id, request.justificativa()));
    }

    @PostMapping(value = "/consultorio/api/atendimentos/{id}/receitas", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> criarReceita(@PathVariable long id, @RequestParam(required = false) String conta,
                                                            @RequestBody ConsultorioUseCase.ReceitaInBound receita,
                                                            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.criarReceita(contexto(conta, login), id, receita));
    }

    @PutMapping(value = "/consultorio/api/receitas/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> editarReceita(@PathVariable long id, @RequestParam(required = false) String conta,
                                                             @RequestBody ConsultorioUseCase.ReceitaInBound receita,
                                                             @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.editarReceita(contexto(conta, login), id, receita));
    }

    @PostMapping("/consultorio/api/receitas/{id}/liberar")
    public ResponseEntity<Map<String, Object>> liberarReceita(@PathVariable long id, @RequestParam(required = false) String conta,
                                                              @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.liberarReceita(contexto(conta, login), id));
    }

    @PostMapping(value = "/consultorio/api/receitas/{id}/cancelar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> cancelarReceita(@PathVariable long id, @RequestParam(required = false) String conta,
                                                               @RequestBody JustificativaRequest request,
                                                               @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.cancelarReceita(contexto(conta, login), id, request.justificativa()));
    }

    @GetMapping("/consultorio/api/receitas/{id}/impressao")
    public ResponseEntity<byte[]> impressao(@PathVariable long id, @RequestParam(required = false) String conta,
                                            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                            HttpServletRequest request) {
        ConsultorioUseCase.Impressao impressao = consultorio.impressaoReceita(contexto(conta, login), id, AdminAcesso.ip(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(impressao.html().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping(value = "/consultorio/api/atendimentos/{id}/finalizar", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> finalizar(@PathVariable long id, @RequestParam(required = false) String conta,
                                                         @RequestBody ConsultorioUseCase.FinalizarInBound finalizacao,
                                                         @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(consultorio.finalizar(contexto(conta, login), id, finalizacao));
    }

    @GetMapping(value = "/consultorio/api/pacientes/{id}/historico", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Atendimento.Prontuario>> historico(@PathVariable long id, @RequestParam(required = false) String conta,
                                                                  @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login,
                                                                  HttpServletRequest request) {
        return semCache(consultorio.historicoDoPaciente(contexto(conta, login), id, AdminAcesso.ip(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalido(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(Proibido.class)
    public ResponseEntity<Map<String, Object>> proibido(Proibido ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(AcessoNegadoException.class)
    public ResponseEntity<Map<String, Object>> acessoNegado(AcessoNegadoException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(AtendimentoDesabilitadoException.class)
    public ResponseEntity<Map<String, Object>> desabilitado(AtendimentoDesabilitadoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("erro", ex.getMessage()));
    }

    private static ResponseEntity<Map<String, Object>> operacao(Atendimento.Operacao operacao) {
        return AtendimentoController.operacao(operacao, "id");
    }

    private static <T> ResponseEntity<T> semCache(T corpo) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(corpo);
    }

    /** Logins das variáveis de ambiente não têm id e, portanto, não têm vínculo com profissional. */
    Contexto contexto(String pedida, AdminAcesso.Login login) {
        String escolhida = conta(pedida, login);
        if (login.id() == null) {
            throw new AcessoNegadoException("Seu usuário não está vinculado a um profissional");
        }
        return new Contexto(escolhida, login.id(), login.usuario());
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
}
