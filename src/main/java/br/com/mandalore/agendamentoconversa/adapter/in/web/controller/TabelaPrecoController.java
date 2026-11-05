package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import br.com.mandalore.agendamentoconversa.adapter.in.web.curadoria.CuradoriaAuthFilter;
import br.com.mandalore.agendamentoconversa.application.domain.model.Orcamento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.TabelaPrecoUseCase;

/** Tabela de preços do orçamento por foto, sob o Basic Auth da curadoria. */
@RestController
@ConditionalOnProperty(
        name = {"curadoria.enabled", "orcamento.enabled", "observability.postgres.enabled"},
        havingValue = "true")
public class TabelaPrecoController {

    private static final Logger LOG = LoggerFactory.getLogger(TabelaPrecoController.class);
    private static final int LIMITE_ARQUIVO = 2 * 1_048_576;

    private final TabelaPrecoUseCase tabela;

    public TabelaPrecoController(TabelaPrecoUseCase tabela) {
        this.tabela = tabela;
    }

    @GetMapping(value = "/curadoria/api/tabela-precos", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Orcamento.ItemPreco> itens() {
        return tabela.itens();
    }

    @PutMapping("/curadoria/api/tabela-precos")
    public ResponseEntity<Map<String, Object>> importar(
            @RequestBody byte[] csv,
            @RequestAttribute(CuradoriaAuthFilter.USUARIO) String usuario
    ) {
        if (csv.length > LIMITE_ARQUIVO) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Arquivo acima de 2 MB"));
        }
        try {
            int itens = tabela.importar(csv);
            LOG.info("tabela de precos importada itens={} usuario={}", itens, usuario);
            return ResponseEntity.ok(Map.of("itens", itens));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
        }
    }
}
