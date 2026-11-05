package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.curadoria.CuradoriaAuthFilter;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.TabelaPrecoUseCase;

/** Tela do financeiro para a tabela de preços do orçamento por foto (Basic Auth PRECOS_USER). */
@RestController
@ConditionalOnProperty(name = {"orcamento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class TabelaPrecoController {

    private static final int LIMITE_ARQUIVO = 2 * 1_048_576;

    private final TabelaPrecoUseCase tabela;
    private final String chatwootUrl;

    public TabelaPrecoController(
            TabelaPrecoUseCase tabela,
            @Value("${precos.chatwoot-url:}") String chatwootUrl
    ) {
        this.tabela = tabela;
        String url = chatwootUrl == null ? "" : chatwootUrl.trim().replaceAll("/+$", "");
        this.chatwootUrl = url.matches("https?://[A-Za-z0-9.:-]+") ? url : "";
    }

    public record ItemRequest(String nome, List<String> sinonimos, BigDecimal valor, String prazo) {
    }

    @GetMapping("/precos")
    public ResponseEntity<byte[]> pagina() throws IOException {
        try (InputStream html = new ClassPathResource("precos/index.html").getInputStream()) {
            String pagina = new String(html.readAllBytes(), StandardCharsets.UTF_8).replace("__CHATWOOT_URL__", chatwootUrl);
            return ResponseEntity.ok()
                    .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                    .body(pagina.getBytes(StandardCharsets.UTF_8));
        }
    }

    @GetMapping(value = "/precos/api/eu", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> eu(@RequestAttribute(CuradoriaAuthFilter.USUARIO) String usuario) {
        return Map.of("usuario", usuario);
    }

    @GetMapping(value = "/precos/api/itens", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Orcamento.ItemPreco> itens() {
        return tabela.itens();
    }

    @PutMapping(value = "/precos/api/itens/{codigo}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> salvar(
            @PathVariable String codigo,
            @RequestBody ItemRequest request,
            @RequestAttribute(CuradoriaAuthFilter.USUARIO) String usuario
    ) {
        try {
            tabela.salvar(new Orcamento.ItemPreco(codigo, request.nome(), request.sinonimos(), request.valor(),
                    request.prazo()), usuario);
            return ResponseEntity.ok(Map.of("codigo", codigo));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
        }
    }

    @DeleteMapping("/precos/api/itens/{codigo}")
    public ResponseEntity<Void> remover(
            @PathVariable String codigo,
            @RequestAttribute(CuradoriaAuthFilter.USUARIO) String usuario
    ) {
        return tabela.remover(codigo, usuario) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PutMapping("/precos/api/importar")
    public ResponseEntity<Map<String, Object>> importar(
            @RequestBody byte[] csv,
            @RequestAttribute(CuradoriaAuthFilter.USUARIO) String usuario
    ) {
        if (csv.length > LIMITE_ARQUIVO) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Arquivo acima de 2 MB"));
        }
        try {
            return ResponseEntity.ok(Map.of("itens", tabela.importar(csv, usuario)));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
        }
    }

    @GetMapping("/precos/api/exportar")
    public ResponseEntity<byte[]> exportar() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"tabela-precos-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(tabela.exportar());
    }
}
