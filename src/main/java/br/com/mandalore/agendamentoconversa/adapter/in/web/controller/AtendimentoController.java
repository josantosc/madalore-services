package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAcesso;
import br.com.mandalore.agendamentoconversa.adapter.in.web.admin.AdminAuthFilter;
import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.AtendimentoUseCase;

/**
 * API da tela da recepção (/atendimento): fila do dia, chegada e desfazer chegada. Nunca devolve conteúdo clínico.
 * O parâmetro {@code conta} escolhe a clínica; o login de uma clínica só enxerga a própria conta.
 */
@RestController
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class AtendimentoController {

    private final AtendimentoUseCase atendimento;
    private final String contaPadrao;

    public AtendimentoController(AtendimentoUseCase atendimento, @Value("${agenda.conta-padrao:1}") String contaPadrao) {
        this.atendimento = atendimento;
        this.contaPadrao = contaPadrao == null || contaPadrao.isBlank() ? "1" : contaPadrao.trim();
    }

    public record DesfazerRequest(String motivo) {
    }

    static final class Proibido extends RuntimeException {
        Proibido() {
            super("Seu usuário não tem acesso ao atendimento desta clínica");
        }
    }

    @GetMapping("/atendimento")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("atendimento/index.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/atendimento/api/fila", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Atendimento.FilaItem> fila(
            @RequestParam(required = false) String conta,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data,
            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login
    ) {
        return atendimento.fila(conta(conta, login), data);
    }

    @PostMapping("/atendimento/api/agendamentos/{id}/chegada")
    public ResponseEntity<Map<String, Object>> chegada(@PathVariable long id, @RequestParam(required = false) String conta,
                                                       @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(atendimento.registrarChegada(conta(conta, login), id, login.usuario()), "atendimentoId");
    }

    @PostMapping(value = "/atendimento/api/atendimentos/{id}/desfazer-chegada", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> desfazerChegada(@PathVariable long id, @RequestParam(required = false) String conta,
                                                               @RequestBody DesfazerRequest request,
                                                               @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return operacao(atendimento.desfazerChegada(conta(conta, login), id, request.motivo(), login.usuario()), "atendimentoId");
    }

    @GetMapping(value = "/atendimento/api/atendimentos/{id}/eventos", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Atendimento.Evento> eventos(@PathVariable long id, @RequestParam(required = false) String conta,
                                            @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return atendimento.eventos(conta(conta, login), id);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalido(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(Proibido.class)
    public ResponseEntity<Map<String, Object>> proibido(Proibido ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("erro", ex.getMessage()));
    }

    @ExceptionHandler(AtendimentoDesabilitadoException.class)
    public ResponseEntity<Map<String, Object>> desabilitado(AtendimentoDesabilitadoException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("erro", ex.getMessage()));
    }

    /** 200 {codigo, campoId} no sucesso; 409 {codigo, erro} na regra de negócio. */
    static ResponseEntity<Map<String, Object>> operacao(Atendimento.Operacao operacao, String campoId) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("codigo", operacao.codigo().name());
        corpo.put(campoId, operacao.id());
        if (operacao.codigo() == CodigoNegocio.SUCESSO) {
            return ResponseEntity.ok(corpo);
        }
        corpo.put("erro", mensagem(operacao.codigo()));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(corpo);
    }

    static String mensagem(CodigoNegocio codigo) {
        return switch (codigo) {
            case NAO_ENCONTRADO, AGENDAMENTO_NAO_ENCONTRADO -> "Registro não encontrado. Atualize a tela.";
            case CHEGADA_FORA_DO_DIA -> "A chegada só pode ser registrada no dia da consulta.";
            case STATUS_INVALIDO_PARA_CHEGADA -> "Só dá para registrar a chegada de consulta marcada ou confirmada. Atualize a tela.";
            case ATENDIMENTO_EM_ANDAMENTO -> "A consulta já começou: não dá para desfazer a chegada.";
            case STATUS_INVALIDO_PARA_CONSULTA -> "Só dá para iniciar a consulta de quem está aguardando. Atualize a tela.";
            case PROFISSIONAL_EM_CONSULTA -> "Você já tem outra consulta em andamento. Finalize-a antes de iniciar esta.";
            case ATENDIMENTO_NAO_EM_CONSULTA -> "O atendimento não está em consulta: não dá para alterar o registro.";
            case REGISTRO_JA_LIBERADO -> "O registro já foi liberado e não pode ser editado. Inative-o com justificativa.";
            case REGISTRO_NAO_LIBERADO -> "Rascunho não se inativa: edite-o ou libere-o.";
            case PROFISSIONAL_SEM_CONSELHO -> "Preencha o número e a UF do conselho no cadastro do profissional antes de liberar a receita.";
            case SEM_REGISTRO_CLINICO -> "Libere a anamnese ou uma evolução antes de finalizar.";
            case RASCUNHO_PENDENTE -> "Há rascunho aberto (anamnese, evolução ou receita). Libere ou descarte antes de finalizar.";
            case DIAGNOSTICO_PRINCIPAL_JA_EXISTE -> "Já existe um diagnóstico principal ativo neste atendimento.";
            case DIAGNOSTICO_DUPLICADO -> "Este CID já está registrado neste atendimento.";
            default -> "Não foi possível gravar (" + codigo.name() + ").";
        };
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
