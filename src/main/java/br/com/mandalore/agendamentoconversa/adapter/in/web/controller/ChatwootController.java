package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;

import br.com.mandalore.agendamentoconversa.adapter.cliente.ClientesProperties;
import br.com.mandalore.agendamentoconversa.application.domain.model.Anexo;
import br.com.mandalore.agendamentoconversa.application.domain.model.Canal;
import br.com.mandalore.agendamentoconversa.application.domain.model.Funil;
import br.com.mandalore.agendamentoconversa.application.domain.model.MensagemEntrada;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.FunilUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.ReceberMensagemUseCase;

@RestController
public class ChatwootController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatwootController.class);

    private final ReceberMensagemUseCase receberMensagem;
    private final String segredoGlobal;
    private final ClientesProperties clientes;
    private final Supplier<FunilUseCase> funil;

    @Autowired
    public ChatwootController(
            ReceberMensagemUseCase receberMensagem,
            @Value("${chatwoot.webhook-secret:}") String segredo,
            ClientesProperties clientes,
            ObjectProvider<FunilUseCase> funil
    ) {
        this(receberMensagem, segredo, clientes, (Supplier<FunilUseCase>) funil::getIfAvailable);
    }

    public ChatwootController(ReceberMensagemUseCase receberMensagem, String segredo, ClientesProperties clientes) {
        this(receberMensagem, segredo, clientes, (Supplier<FunilUseCase>) () -> null);
    }

    ChatwootController(ReceberMensagemUseCase receberMensagem, String segredo, ClientesProperties clientes,
                       Supplier<FunilUseCase> funil) {
        this.receberMensagem = receberMensagem;
        this.segredoGlobal = segredo;
        this.clientes = clientes;
        this.funil = funil;
    }

    @PostMapping("/chatwoot")
    public ResponseEntity<Void> receber(
            @RequestParam(name = "token", required = false) String tokenQuery,
            @RequestHeader(name = "token", required = false) String tokenHeader,
            @RequestHeader(name = "X-Chatwoot-Token", required = false) String tokenAlternativo,
            @RequestBody JsonNode corpo
    ) {
        String informado = primeiro(tokenQuery, tokenHeader, tokenAlternativo);
        MensagemEntrada mensagem = ler(corpo);
        if (!segredoConfere(segredo(mensagem.accountId()), informado)) {
            LOG.warn("chatwoot webhook recusado account_id={} conversation_id={} token_informado={}",
                    mensagem.accountId(), mensagem.conversationId(), informado != null);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        receberMensagem.receber(mensagem);
        FunilUseCase oportunidades = funil.get();
        if (oportunidades != null) {
            eventoFunil(corpo, mensagem.accountId()).ifPresent(evento -> {
                try {
                    oportunidades.registrar(evento);
                } catch (RuntimeException ex) {
                    LOG.warn("conversation_id={} funil evento={} falhou: {}",
                            evento.conversa(), evento.tipo(), ex.getClass().getSimpleName());
                }
            });
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Mensagem do paciente, resposta do atendente (conversa aberta, não privada, não do bot) e conversa atualizada
     * (etiqueta adicionada ou atribuição). A resposta do bot sai pela API com a conversa pendente; por isso só conta a
     * mensagem com a conversa aberta. Nome do contato e do atendente atribuído vêm de {@code meta} da conversa.
     */
    static Optional<Funil.Evento> eventoFunil(JsonNode corpo, String accountId) {
        String event = corpo.path("event").asText("");
        if (accountId == null) {
            return Optional.empty();
        }
        if ("conversation_updated".equals(event)) {
            String conversa = corpo.path("id").asText(null);
            List<String> adicionadas = etiquetasAdicionadas(corpo.path("changed_attributes"));
            String paciente = nome(corpo.path("meta").path("sender"));
            String atribuido = nome(corpo.path("meta").path("assignee"));
            return conversa == null || adicionadas.isEmpty() && paciente == null && atribuido == null ? Optional.empty()
                    : Optional.of(new Funil.Evento(Funil.Evento.Tipo.CONVERSA, accountId, conversa, null, adicionadas,
                            paciente, atribuido));
        }
        JsonNode conversation = corpo.path("conversation");
        String conversa = conversation.path("id").asText(null);
        if (!"message_created".equals(event) || conversa == null || corpo.path("private").asBoolean(false)) {
            return Optional.empty();
        }
        String tipo = corpo.path("message_type").asText("");
        JsonNode sender = corpo.path("sender");
        String paciente = nome(conversation.path("meta").path("sender"));
        String atribuido = nome(conversation.path("meta").path("assignee"));
        if ("incoming".equalsIgnoreCase(tipo) || "0".equals(tipo)) {
            if (paciente == null && "contact".equalsIgnoreCase(sender.path("type").asText(""))) {
                paciente = nome(sender);
            }
            return Optional.of(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_PACIENTE, accountId, conversa, null, null,
                    paciente, atribuido));
        }
        boolean atendente = ("outgoing".equalsIgnoreCase(tipo) || "1".equals(tipo))
                && "open".equalsIgnoreCase(conversation.path("status").asText(""))
                && !"agent_bot".equalsIgnoreCase(sender.path("type").asText(""));
        return atendente
                ? Optional.of(new Funil.Evento(Funil.Evento.Tipo.MENSAGEM_ATENDENTE, accountId, conversa,
                        nome(sender), null, paciente, atribuido))
                : Optional.empty();
    }

    private static String nome(JsonNode pessoa) {
        String nome = pessoa.path("name").asText("").trim();
        return nome.isEmpty() ? null : nome;
    }

    private String segredo(String accountId) {
        return clientes.cliente(accountId)
                .map(ClientesProperties.Cliente::webhookSecret)
                .filter(segredo -> !segredo.isBlank())
                .orElse(segredoGlobal);
    }

    private MensagemEntrada ler(JsonNode corpo) {
        JsonNode conversation = corpo.path("conversation");
        Canal canal = Canal.doChatwoot(
                texto(conversation, "channel"),
                texto(conversation.path("additional_attributes"), "type")
        );
        String messageType = texto(corpo, "message_type");
        if (messageType == null && corpo.path("message_type").isNumber()) {
            messageType = corpo.path("message_type").asText();
        }
        return new MensagemEntrada(
                texto(corpo, "event"),
                messageType,
                corpo.path("private").asBoolean(false),
                texto(corpo, "content"),
                corpo.path("id").isMissingNode() ? null : corpo.path("id").asText(),
                corpo.path("account").path("id").asText(corpo.path("account_id").asText(null)),
                conversation.path("id").asText(null),
                conversation.path("status").asText(null),
                canal,
                anexos(corpo.path("attachments"))
        );
    }

    /** changed_attributes: [{"label_list": {"previous_value": [...], "current_value": [...]}}]; a lista pode vir como texto "a,b". */
    private static List<String> etiquetasAdicionadas(JsonNode mudancas) {
        List<String> adicionadas = new ArrayList<>();
        if (!mudancas.isArray()) {
            return adicionadas;
        }
        mudancas.forEach(mudanca -> List.of("label_list", "cached_label_list").forEach(campo -> {
            JsonNode etiquetas = mudanca.path(campo);
            if (etiquetas.isObject()) {
                List<String> antes = lista(etiquetas.path("previous_value"));
                lista(etiquetas.path("current_value")).stream()
                        .filter(e -> !antes.contains(e) && !adicionadas.contains(e))
                        .forEach(adicionadas::add);
            }
        }));
        return adicionadas;
    }

    private static List<String> lista(JsonNode valor) {
        List<String> itens = new ArrayList<>();
        if (valor.isArray()) {
            valor.forEach(item -> itens.add(item.asText().trim()));
        } else if (valor.isTextual()) {
            for (String item : valor.asText().split(",")) {
                if (!item.isBlank()) {
                    itens.add(item.trim());
                }
            }
        }
        return itens;
    }

    private List<Anexo> anexos(JsonNode attachments) {
        List<Anexo> anexos = new ArrayList<>();
        if (attachments.isArray()) {
            attachments.forEach(item -> anexos.add(new Anexo(texto(item, "file_type"), texto(item, "data_url"))));
        }
        return anexos;
    }

    private boolean segredoConfere(String segredo, String informado) {
        if (segredo == null || segredo.isBlank() || informado == null) {
            return false;
        }
        return MessageDigest.isEqual(
                segredo.getBytes(StandardCharsets.UTF_8),
                informado.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String primeiro(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor;
            }
        }
        return null;
    }

    private String texto(JsonNode node, String campo) {
        JsonNode valor = node.path(campo);
        if (valor.isMissingNode() || valor.isNull()) {
            return null;
        }
        return valor.asText();
    }
}
