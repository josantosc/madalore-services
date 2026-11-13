package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
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
import br.com.mandalore.agendamentoconversa.application.domain.exception.AtendimentoDesabilitadoException;
import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.MedicamentoRotinaUseCase;

/**
 * Medicamentos de rotina: a tela do gestor (/consultorio/medicamentos, módulo MEDICAMENTOS_ROTINA) e a lista que a
 * receita do médico consulta (/consultorio/api/medicamentos-rotina, módulo CONSULTORIO).
 */
@RestController
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class MedicamentoRotinaController {

    private final MedicamentoRotinaUseCase medicamentos;
    private final String contaPadrao;

    public MedicamentoRotinaController(MedicamentoRotinaUseCase medicamentos,
                                       @Value("${agenda.conta-padrao:1}") String contaPadrao) {
        this.medicamentos = medicamentos;
        this.contaPadrao = contaPadrao == null || contaPadrao.isBlank() ? "1" : contaPadrao.trim();
    }

    static final class Proibido extends RuntimeException {
        Proibido() {
            super("Seu usuário não tem acesso a esta clínica");
        }
    }

    @GetMapping("/consultorio/medicamentos")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("consultorio/medicamentos.html").getInputStream()) {
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(html.readAllBytes());
        }
    }

    @GetMapping(value = "/consultorio/medicamentos/api/contexto", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> contexto(@RequestParam(required = false) String conta,
                                        @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        String escolhida = conta(conta, login);
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("conta", escolhida);
        corpo.put("contas", contasVisiveis(login, escolhida));
        corpo.put("itens", medicamentos.listar(escolhida));
        return corpo;
    }

    @PutMapping(value = "/consultorio/medicamentos/api/itens", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> salvar(@RequestParam(required = false) String conta,
                                                      @RequestBody Atendimento.MedicamentoRotina item,
                                                      @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return AtendimentoController.operacao(medicamentos.salvar(conta(conta, login), item, login.usuario()), "id");
    }

    @DeleteMapping("/consultorio/medicamentos/api/itens/{id}")
    public ResponseEntity<Void> excluir(@PathVariable long id, @RequestParam(required = false) String conta,
                                        @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return medicamentos.excluir(conta(conta, login), id)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping(value = "/consultorio/medicamentos/api/restaurar", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> restaurar(@RequestParam(required = false) String conta,
                                         @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return Map.of("incluidos", medicamentos.restaurarPadrao(conta(conta, login), login.usuario()));
    }

    @GetMapping(value = "/consultorio/api/medicamentos-rotina", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Atendimento.MedicamentoRotina> daReceita(@RequestParam(required = false) String conta,
                                                         @RequestAttribute(AdminAuthFilter.LOGIN) AdminAcesso.Login login) {
        return medicamentos.listar(conta(conta, login));
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

    /** Sem conta escolhida: a primeira clínica do login ou a conta padrão. */
    String conta(String pedida, AdminAcesso.Login login) {
        String conta = pedida == null || pedida.isBlank()
                ? login.contas().isEmpty() ? contaPadrao : new TreeSet<>(login.contas()).first()
                : pedida.trim();
        if (!login.podeVer(conta)) {
            throw new Proibido();
        }
        return conta;
    }

    private List<String> contasVisiveis(AdminAcesso.Login login, String escolhida) {
        TreeSet<String> contas = new TreeSet<>(login.contas());
        if (contas.isEmpty()) {
            contas.add(contaPadrao);
            contas.add(escolhida);
        }
        return List.copyOf(contas);
    }
}
