package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.ContextoConversa;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PaginaLista;

@Component
public class ContextoCodec {

    private final ObjectMapper mapper;

    public ContextoCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ContextoConversa ler(JsonNode outputContexts) {
        ContextoConversa ctx = new ContextoConversa();
        if (outputContexts == null || !outputContexts.isArray()) {
            return ctx;
        }
        for (JsonNode node : outputContexts) {
            String nome = ultimo(node.path("name").asText(""));
            JsonNode params = node.path("parameters");
            switch (nome) {
                case "paciente" -> {
                    ctx.setPacienteId(texto(params, "paciente_id"));
                    ctx.setNome(texto(params, "nome"));
                }
                case "paciente_sem_cadastro" -> {
                    ctx.setSemCadastro(true);
                    ctx.setCpf(texto(params, "cpf"));
                    ctx.setNascimento(Textos.data(texto(params, "nascimento")));
                    ctx.setNome(texto(params, "nome"));
                }
                case "convenio" -> {
                    ctx.setConvenioId(texto(params, "cd_convenio"));
                    ctx.setConvenioNome(texto(params, "nome"));
                }
                case "profissional" -> {
                    ctx.setMedicoId(texto(params, "cd_medico"));
                    ctx.setMedicoNome(texto(params, "nome"));
                }
                case "especialidade" -> {
                    ctx.setEspecialidadeId(texto(params, "cd_especialidade"));
                    ctx.setEspecialidadeNome(texto(params, "nome"));
                }
                case "exame" -> {
                    ctx.setExame(true);
                    ctx.setExameId(texto(params, "cd_exame"));
                    ctx.setExameNome(texto(params, "nome"));
                }
                case "periodo" -> ctx.setPeriodo(texto(params, "periodo"));
                case "dia" -> {
                    ctx.setDia(Textos.data(texto(params, "data")));
                    ctx.setSemana("true".equals(texto(params, "semana")));
                }
                case "oferta" -> ctx.setOferta(lerLista(params, "horarios", new TypeReference<List<HorarioOferta>>() {
                }));
                case "agendamentos" -> {
                    ctx.setAgendamentos(lerLista(params, "itens", new TypeReference<List<AgendamentoAberto>>() {
                    }));
                    ctx.setAgendamentoAtualId(texto(params, "atual"));
                }
                case "aguardando_confirmacao" -> {
                    ctx.setConfirmacaoNr(texto(params, "nr_sequencia"));
                    ctx.setConfirmacaoResumo(texto(params, "resumo"));
                    ctx.setConfirmacaoTipo(texto(params, "tipo"));
                    ctx.setConfirmacaoCdAgenda(texto(params, "cd_agenda"));
                    ctx.setConfirmacaoEspecialidade(texto(params, "especialidade"));
                    ctx.setConfirmacaoMedico(texto(params, "medico"));
                    ctx.setConfirmacaoLocal(texto(params, "local"));
                    String dataHora = texto(params, "data_hora");
                    if (dataHora != null) {
                        ctx.setConfirmacaoDataHora(LocalDateTime.parse(dataHora));
                    }
                }
                case "lista" -> ctx.setLista(lerPagina(params));
                case "agendamento_futuro" -> {
                    String quantidade = texto(params, "quantidade");
                    if (quantidade != null) {
                        ctx.setQuantidadeFutura(Integer.valueOf(quantidade));
                    }
                    ctx.setQuandoProximo(texto(params, "quando"));
                }
                case "intencao_consulta" -> ctx.setObjetivo("consulta");
                case "intencao_agendamentos" -> ctx.setObjetivo("agendamentos");
                default -> {
                }
            }
        }
        return ctx;
    }

    public List<Map<String, Object>> escrever(String session, ContextoConversa ctx) {
        List<Map<String, Object>> saida = new ArrayList<>();
        if (ctx.temPaciente() || ctx.isSemCadastro() && ctx.getNome() != null) {
            saida.add(contexto(session, "paciente", 20, mapa(
                    "paciente_id", ctx.getPacienteId(),
                    "nome", ctx.getNome()
            )));
        }
        if (ctx.isSemCadastro()) {
            saida.add(contexto(session, "paciente_sem_cadastro", 20, mapa(
                    "cpf", ctx.getCpf(),
                    "nascimento", ctx.getNascimento() == null ? null : ctx.getNascimento().toString(),
                    "nome", ctx.getNome()
            )));
        }
        if (ctx.getConvenioId() != null) {
            saida.add(contexto(session, "convenio", 15, mapa(
                    "cd_convenio", ctx.getConvenioId(),
                    "nome", ctx.getConvenioNome()
            )));
        }
        if (ctx.isLimparProfissional()) {
            saida.add(contexto(session, "profissional", 0, Map.of()));
        } else if (ctx.getMedicoId() != null) {
            saida.add(contexto(session, "profissional", 15, mapa(
                    "cd_medico", ctx.getMedicoId(),
                    "nome", ctx.getMedicoNome()
            )));
        }
        if (ctx.getEspecialidadeId() != null) {
            saida.add(contexto(session, "especialidade", 15, mapa(
                    "cd_especialidade", ctx.getEspecialidadeId(),
                    "nome", ctx.getEspecialidadeNome()
            )));
        }
        if (ctx.isExame()) {
            saida.add(contexto(session, "exame", 20, mapa(
                    "cd_exame", ctx.getExameId(),
                    "nome", ctx.getExameNome()
            )));
        }
        if (ctx.getPeriodo() != null && !ctx.getPeriodo().isBlank()) {
            saida.add(contexto(session, "periodo", 10, mapa("periodo", ctx.getPeriodo())));
        }
        if (ctx.getDia() != null) {
            saida.add(contexto(session, "dia", 10, mapa(
                    "data", ctx.getDia().toString(),
                    "semana", Boolean.toString(ctx.isSemana())
            )));
        }
        // O Dialogflow grava os parâmetros da intent em todos os contextos ativos: nenhuma chave daqui pode ter nome de parâmetro de intent.
        if (ctx.getOferta() != null && !ctx.getOferta().isEmpty()) {
            saida.add(contexto(session, "oferta", 5, mapa("horarios", json(ctx.getOferta()))));
        }
        if ((ctx.getAgendamentos() != null && !ctx.getAgendamentos().isEmpty()) || ctx.getAgendamentoAtualId() != null) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("itens", json(ctx.getAgendamentos()));
            if (ctx.getAgendamentoAtualId() != null) {
                params.put("atual", ctx.getAgendamentoAtualId());
            }
            saida.add(contexto(session, "agendamentos", 5, params));
        }
        if (ctx.isLimparConfirmacao()) {
            saida.add(contexto(session, "aguardando_confirmacao", 0, Map.of()));
        } else if (ctx.temConfirmacao()) {
            saida.add(contexto(session, "aguardando_confirmacao", 2, mapa(
                    "nr_sequencia", ctx.getConfirmacaoNr(),
                    "resumo", ctx.getConfirmacaoResumo(),
                    "tipo", ctx.getConfirmacaoTipo(),
                    "cd_agenda", ctx.getConfirmacaoCdAgenda(),
                    "data_hora", ctx.getConfirmacaoDataHora() == null ? null : ctx.getConfirmacaoDataHora().toString(),
                    "especialidade", ctx.getConfirmacaoEspecialidade(),
                    "medico", ctx.getConfirmacaoMedico(),
                    "local", ctx.getConfirmacaoLocal()
            )));
        }
        if (ctx.getLista() != null) {
            saida.add(contexto(session, "lista", 3, mapa("pagina", json(ctx.getLista()))));
        }
        if (ctx.getQuantidadeFutura() != null) {
            saida.add(contexto(session, "agendamento_futuro", 3, mapa(
                    "quantidade", String.valueOf(ctx.getQuantidadeFutura()),
                    "quando", ctx.getQuandoProximo()
            )));
        }
        if (ctx.isLimparObjetivo()) {
            saida.add(contexto(session, "intencao_consulta", 0, Map.of()));
            saida.add(contexto(session, "intencao_agendamentos", 0, Map.of()));
        }
        return saida;
    }

    private PaginaLista lerPagina(JsonNode params) {
        String payload = texto(params, "pagina");
        if (payload == null) {
            return null;
        }
        try {
            return mapper.readValue(payload, PaginaLista.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private <T> List<T> lerLista(JsonNode params, String chave, TypeReference<List<T>> tipo) {
        String payload = texto(params, chave);
        if (payload == null) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(payload, tipo);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> contexto(String session, String nome, int lifespan, Map<String, String> parametros) {
        Map<String, Object> corpo = new LinkedHashMap<>();
        corpo.put("name", session + "/contexts/" + nome);
        corpo.put("lifespanCount", lifespan);
        corpo.put("parameters", parametros);
        return corpo;
    }

    private Map<String, String> mapa(String... pares) {
        Map<String, String> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            if (pares[i + 1] != null) {
                mapa.put(pares[i], pares[i + 1]);
            }
        }
        return mapa;
    }

    private String json(Object valor) {
        try {
            return mapper.writeValueAsString(valor);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private String texto(JsonNode params, String campo) {
        JsonNode node = params.path(campo);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String valor = node.asText();
        return valor.isBlank() ? null : valor;
    }

    private String ultimo(String name) {
        int barra = name.lastIndexOf('/');
        return barra < 0 ? name : name.substring(barra + 1);
    }

}
