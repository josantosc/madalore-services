package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.exception.ConsultaIndisponivelException;
import br.com.mandalore.agendamentoconversa.application.domain.model.AgendamentoAberto;
import br.com.mandalore.agendamentoconversa.application.domain.model.CodigoNegocio;
import br.com.mandalore.agendamentoconversa.application.domain.model.ContextoConversa;
import br.com.mandalore.agendamentoconversa.application.domain.model.FiltroDisponibilidade;
import br.com.mandalore.agendamentoconversa.application.domain.model.FulfillmentCommand;
import br.com.mandalore.agendamentoconversa.application.domain.model.HorarioOferta;
import br.com.mandalore.agendamentoconversa.application.domain.model.IdentidadePaciente;
import br.com.mandalore.agendamentoconversa.application.domain.model.ItemCatalogo;
import br.com.mandalore.agendamentoconversa.application.domain.model.PacienteCadastro;
import br.com.mandalore.agendamentoconversa.application.domain.model.PaginaLista;
import br.com.mandalore.agendamentoconversa.application.domain.model.PedidoAgendar;
import br.com.mandalore.agendamentoconversa.application.domain.model.ProcedimentoExame;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoEscrita;
import br.com.mandalore.agendamentoconversa.application.domain.model.ResultadoOperacao;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendaConsultaPort;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.AgendamentoEscritaPort;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowEvent;
import br.com.mandalore.agendamentoconversa.application.domain.observability.FlowTelemetry;

@Service
public class AgendaConsultaService {

    private static final Logger LOG = LoggerFactory.getLogger(AgendaConsultaService.class);

    private final AgendaConsultaPort agenda;
    private final AgendamentoEscritaPort escrita;
    private final IdentidadeSessao identidades;
    private final FlowTelemetry telemetry;

    public AgendaConsultaService(
            AgendaConsultaPort agenda,
            AgendamentoEscritaPort escrita,
            IdentidadeSessao identidades,
            FlowTelemetry telemetry
    ) {
        this.agenda = agenda;
        this.escrita = escrita;
        this.identidades = identidades;
        this.telemetry = telemetry;
    }

    public ResultadoOperacao executar(FulfillmentCommand command) {
        if (!command.acao()) {
            return indisponivel(command.contexto());
        }
        if (command.intent().startsWith("acao.exame.")) {
            command.contexto().setExame(true);
        }
        try {
            return switch (command.operacao()) {
                case "identificar" -> identificar(command);
                case "sem_cadastro" -> semCadastro(command);
                case "convenios" -> convenios(command);
                case "profissionais" -> profissionais(command);
                case "especialidades" -> especialidades(command);
                case "exames" -> exames(command);
                case "escolher_lista" -> escolherLista(command);
                case "disponibilidade" -> disponibilidade(command);
                case "meus_agendamentos" -> meusAgendamentos(command);
                case "escolher" -> escolher(command);
                case "agendar" -> agendar(command);
                case "confirmar" -> alterarExistente(command, "confirmar");
                case "cancelar" -> alterarExistente(command, "cancelar");
                case "remarcar" -> remarcar(command);
                default -> indisponivel(command.contexto());
            };
        } catch (ConsultaIndisponivelException ex) {
            LOG.warn("agenda indisponivel operacao={} motivo={}", command.operacao(), ex.getMessage(), ex);
            String conversationId = Textos.conversationId(command.session());
            telemetry.record(FlowEvent.builder("TASY", "agenda." + command.operacao(), "ERROR")
                    .correlation(conversationId, conversationId)
                    .intent(command.intent(), command.operacao())
                    .error("TASY_QUERY_FAILED", ex.getCause() == null ? ex : ex.getCause())
                    .build());
            return indisponivel(command.contexto());
        }
    }

    private ResultadoOperacao identificar(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        String cpf = Textos.digitosCpf(Textos.texto(command.parameters(), "cpf"));
        if (cpf.length() != 11) {
            cpf = Textos.digitosCpf(command.queryText());
        }
        LocalDate nascimento = Textos.data(command.parameters().get("data_nascimento"));
        if (nascimento == null) {
            nascimento = Textos.data(command.queryText());
        }
        if (cpf.length() != 11 || nascimento == null) {
            return evento("RESULTADO_NAO_IDENTIFICADO", Map.of(), ctx, CodigoNegocio.NAO_IDENTIFICADO);
        }
        List<PacienteCadastro> pessoas = agenda.identificar(cpf, nascimento);
        if (pessoas.isEmpty()) {
            ctx.setSemCadastro(true);
            ctx.setCpf(cpf);
            ctx.setNascimento(nascimento);
            return evento(
                    "RESULTADO_PACIENTE_NAO_ENCONTRADO",
                    Map.of("cpf", cpf, "nascimento", nascimento.toString()),
                    ctx,
                    CodigoNegocio.PACIENTE_NAO_ENCONTRADO
            );
        }
        if (pessoas.size() > 1) {
            return indisponivel(ctx);
        }
        PacienteCadastro pessoa = pessoas.get(0);
        ctx.setSemCadastro(false);
        identidades.guardar(pessoa.id(), new IdentidadePaciente(cpf, nascimento, pessoa.nomeCompleto()));
        ctx.setPacienteId(pessoa.id());
        ctx.setNome(Textos.primeiroNome(pessoa.nomeCompleto()));
        List<AgendamentoAberto> futuros = agenda.agendamentosFuturos(pessoa.id());
        if (futuros.isEmpty()) {
            return evento("RESULTADO_SUCESSO_IDENTIFICAR", Map.of("nome", ctx.getNome()), ctx, CodigoNegocio.SUCESSO);
        }
        ctx.setAgendamentos(futuros);
        ctx.setQuantidadeFutura(futuros.size());
        ctx.setQuandoProximo(Textos.dia(futuros.get(0).dataHora()));
        return evento("RESULTADO_JA_TEM_AGENDAMENTO", Map.of(
                "nome", ctx.getNome(),
                "quando", ctx.getQuandoProximo(),
                "quantidade", String.valueOf(futuros.size())
        ), ctx, CodigoNegocio.JA_TEM_AGENDAMENTO);
    }

    private ResultadoOperacao semCadastro(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        String nome = Textos.texto(command.parameters(), "nome");
        if (!ctx.isSemCadastro() || ctx.getNascimento() == null || ctx.getCpf() == null || nome.isBlank()) {
            return evento("RESULTADO_NAO_IDENTIFICADO", Map.of(), ctx, CodigoNegocio.NAO_IDENTIFICADO);
        }
        ctx.setNome(nome.trim());
        return falarPagina(ctx, Catalogo.primeira("convenio", agenda.convenios(ctx.getPacienteId())));
    }

    private ResultadoOperacao convenios(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temIdentidadeAgendamento()) {
            return naoIdentificado(ctx);
        }
        return falarPagina(ctx, Catalogo.primeira("convenio", agenda.convenios(ctx.getPacienteId())));
    }

    private ResultadoOperacao profissionais(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temIdentidadeAgendamento()) {
            return naoIdentificado(ctx);
        }
        List<ItemCatalogo> medicos = agenda.profissionais(ctx.getPacienteId());
        if (medicos.isEmpty()) {
            return evento("RESULTADO_SEM_HORARIO", Map.of(
                    "especialidade", "consulta",
                    "periodo", "para os próximos dias"
            ), ctx, CodigoNegocio.SEM_HORARIO);
        }
        return falarPagina(ctx, Catalogo.primeira("profissional", medicos));
    }

    private ResultadoOperacao especialidades(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temIdentidadeAgendamento()) {
            return naoIdentificado(ctx);
        }
        String medico = Textos.texto(command.parameters(), "cd_medico");
        if (medico.isBlank()) {
            medico = ctx.getMedicoId();
        }
        return especialidadesDe(ctx, medico);
    }

    private ResultadoOperacao especialidadesDe(ContextoConversa ctx, String medicoId) {
        List<ItemCatalogo> itens = agenda.especialidades(medicoId);
        if (itens.isEmpty()) {
            return evento("RESULTADO_SEM_HORARIO", Map.of(
                    "especialidade", ctx.getEspecialidadeNome() == null ? "consulta" : ctx.getEspecialidadeNome(),
                    "periodo", Textos.periodoFrase(ctx.getPeriodo())
            ), ctx, CodigoNegocio.SEM_HORARIO);
        }
        if (medicoId != null && !medicoId.isBlank() && itens.size() == 1) {
            ItemCatalogo unica = itens.get(0);
            ctx.setEspecialidadeId(unica.id());
            ctx.setEspecialidadeNome(unica.nome());
            return evento("RESULTADO_UMA_ESPECIALIDADE", Map.of(
                    "medico", ctx.getMedicoNome() == null ? "" : ctx.getMedicoNome(),
                    "especialidade", unica.nome()
            ), ctx, CodigoNegocio.SUCESSO);
        }
        return falarPagina(ctx, Catalogo.primeira("especialidade", itens));
    }

    private ResultadoOperacao escolherLista(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        PaginaLista pagina = ctx.getLista();
        if (pagina == null || pagina.exibidos() == null || pagina.exibidos().isEmpty()) {
            return indisponivel(ctx);
        }
        String fala = Textos.normalizar(command.queryText());
        List<ItemCatalogo> varios = Catalogo.buscarNome(pagina, fala);
        if (varios.size() > 1) {
            return falarPagina(ctx, new PaginaLista(pagina.tipo(), 0, pagina.todos(), varios));
        }
        ItemCatalogo escolhido = Catalogo.resolver(pagina, command);
        if (escolhido == null) {
            return falarPagina(ctx, pagina);
        }
        if ("ver_outros".equals(escolhido.acao()) || "ver_mais".equals(escolhido.acao())) {
            return falarPagina(ctx, Catalogo.proxima(pagina));
        }
        if ("outra_pessoa".equals(escolhido.acao())) {
            if ("exame".equals(pagina.tipo())) {
                return falarPagina(ctx, pagina);
            }
            ctx.setMedicoId(null);
            ctx.setMedicoNome(null);
            ctx.setLimparProfissional(true);
            return especialidadesDe(ctx, null);
        }
        return switch (pagina.tipo()) {
            case "convenio" -> {
                ctx.setConvenioId(escolhido.id());
                ctx.setConvenioNome(escolhido.nome());
                if (ctx.isExame()) {
                    yield listarExames(ctx);
                }
                yield evento("RESULTADO_TIPO_BUSCA", Map.of(), ctx, CodigoNegocio.SUCESSO);
            }
            case "exame" -> {
                ctx.setExameId(escolhido.id());
                ctx.setExameNome(escolhido.nome());
                yield evento("RESULTADO_PERIODO", Map.of(), ctx, CodigoNegocio.SUCESSO);
            }
            case "profissional" -> {
                ctx.setMedicoId(escolhido.id());
                ctx.setMedicoNome(escolhido.nome());
                ctx.setEspecialidadeId(null);
                ctx.setEspecialidadeNome(null);
                yield evento("RESULTADO_PERIODO", Map.of(), ctx, CodigoNegocio.SUCESSO);
            }
            case "especialidade" -> {
                ctx.setEspecialidadeId(escolhido.id());
                ctx.setEspecialidadeNome(escolhido.nome());
                ctx.setMedicoId(null);
                ctx.setMedicoNome(null);
                yield evento("RESULTADO_PERIODO", Map.of(), ctx, CodigoNegocio.SUCESSO);
            }
            default -> indisponivel(ctx);
        };
    }

    private ResultadoOperacao proximosHorarios(ContextoConversa ctx) {
        LocalDate inicioDia = LocalDate.now();
        ctx.setDia(inicioDia);
        ctx.setSemana(true);
        if (ctx.getPeriodo() == null || ctx.getPeriodo().isBlank()) {
            ctx.setPeriodo("tanto_faz");
        }
        List<HorarioOferta> horarios = agenda.disponibilidade(new FiltroDisponibilidade(
                ctx.getMedicoId(),
                ctx.getEspecialidadeId(),
                inicioDia.atStartOfDay(),
                inicioDia.plusDays(30).atStartOfDay(),
                ctx.getPeriodo()
        ));
        if (horarios.isEmpty()) {
            return evento("RESULTADO_SEM_HORARIO", Map.of(
                    "especialidade", ctx.getEspecialidadeNome() == null ? "consulta" : ctx.getEspecialidadeNome(),
                    "periodo", Textos.periodoFrase(ctx.getPeriodo()) + " nos próximos 30 dias"
            ), ctx, CodigoNegocio.SEM_HORARIO);
        }
        ctx.setOferta(horarios);
        limparConfirmacao(ctx);
        return evento("RESULTADO_LISTA_HORARIOS", Map.of(
                "lista", Formatador.horarios(ctx.getEspecialidadeNome(), ctx.getPeriodo(), horarios)
        ), ctx, CodigoNegocio.SUCESSO);
    }

    private ResultadoOperacao disponibilidade(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temIdentidadeAgendamento()) {
            return naoIdentificado(ctx);
        }
        aplicarFiltros(command);
        if (ctx.getPeriodo() == null || ctx.getPeriodo().isBlank()) {
            return evento("RESULTADO_PERIODO", Map.of(), ctx, CodigoNegocio.SUCESSO);
        }
        if (ctx.isExame()) {
            return disponibilidadeExame(ctx);
        }
        if (ctx.getDia() == null) {
            return proximosHorarios(ctx);
        }
        if (ctx.getEspecialidadeId() == null || ctx.getEspecialidadeId().isBlank()) {
            return indisponivel(ctx);
        }
        LocalDateTime inicio = ctx.getDia().atStartOfDay();
        LocalDateTime fim = (ctx.isSemana() ? ctx.getDia().plusDays(7) : ctx.getDia().plusDays(1)).atStartOfDay();
        List<HorarioOferta> horarios = agenda.disponibilidade(new FiltroDisponibilidade(
                ctx.getMedicoId(),
                ctx.getEspecialidadeId(),
                inicio,
                fim,
                ctx.getPeriodo()
        ));
        if (horarios.isEmpty()) {
            return evento("RESULTADO_SEM_HORARIO", Map.of(
                    "especialidade", ctx.getEspecialidadeNome() == null ? "" : ctx.getEspecialidadeNome(),
                    "periodo", Textos.periodoFrase(ctx.getPeriodo())
            ), ctx, CodigoNegocio.SEM_HORARIO);
        }
        ctx.setOferta(horarios);
        limparConfirmacao(ctx);
        return evento("RESULTADO_LISTA_HORARIOS", Map.of(
                "lista", Formatador.horarios(ctx.getEspecialidadeNome(), ctx.getPeriodo(), horarios)
        ), ctx, CodigoNegocio.SUCESSO);
    }

    private ResultadoOperacao exames(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temIdentidadeAgendamento()) {
            return naoIdentificado(ctx);
        }
        ctx.setExameId(null);
        ctx.setExameNome(null);
        ctx.setPeriodo(null);
        ctx.setDia(null);
        ctx.setOferta(List.of());
        return listarExames(ctx);
    }

    private ResultadoOperacao listarExames(ContextoConversa ctx) {
        List<ItemCatalogo> exames = agenda.exames();
        if (exames.isEmpty()) {
            return evento("RESULTADO_SEM_EXAME", Map.of(), ctx, CodigoNegocio.SEM_HORARIO);
        }
        return falarPagina(ctx, Catalogo.primeira("exame", exames));
    }

    private ResultadoOperacao disponibilidadeExame(ContextoConversa ctx) {
        if (ctx.getExameId() == null || ctx.getExameId().isBlank()) {
            return listarExames(ctx);
        }
        LocalDate inicioDia = ctx.getDia();
        LocalDate fimDia;
        String janela = "";
        if (inicioDia == null) {
            inicioDia = LocalDate.now();
            fimDia = inicioDia.plusDays(30);
            janela = " nos próximos 30 dias";
            ctx.setDia(inicioDia);
            ctx.setSemana(true);
        } else {
            fimDia = ctx.isSemana() ? inicioDia.plusDays(7) : inicioDia.plusDays(1);
        }
        LocalDateTime inicio = inicioDia.atStartOfDay();
        LocalDateTime agora = LocalDateTime.now();
        if (inicio.isBefore(agora)) {
            inicio = agora;
        }
        List<HorarioOferta> horarios = agenda.disponibilidadeExame(
                ctx.getExameId(),
                inicio,
                fimDia.atStartOfDay(),
                ctx.getPeriodo()
        );
        if (horarios.isEmpty()) {
            return evento("RESULTADO_SEM_HORARIO_EXAME", Map.of(
                    "exame", ctx.getExameNome() == null ? "exame" : ctx.getExameNome(),
                    "periodo", Textos.periodoFrase(ctx.getPeriodo()) + janela
            ), ctx, CodigoNegocio.SEM_HORARIO);
        }
        ctx.setOferta(horarios);
        limparConfirmacao(ctx);
        return evento("RESULTADO_LISTA_HORARIOS", Map.of(
                "lista", Formatador.horarios(ctx.getExameNome(), ctx.getPeriodo(), horarios)
        ), ctx, CodigoNegocio.SUCESSO);
    }

    private String resumo(ContextoConversa ctx, HorarioOferta horario) {
        return ctx.isExame()
                ? Formatador.resumoExame(ctx.getExameNome(), horario, ctx.getConvenioNome())
                : Formatador.resumoMarcacao(ctx.getEspecialidadeNome(), horario, ctx.getConvenioNome());
    }

    private ResultadoOperacao meusAgendamentos(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temPaciente()) {
            return naoIdentificado(ctx);
        }
        List<AgendamentoAberto> itens = agenda.agendamentosFuturos(ctx.getPacienteId());
        ctx.setAgendamentos(itens);
        if (itens.isEmpty()) {
            return evento("RESULTADO_SEM_AGENDAMENTO", Map.of(
                    "nome", ctx.getNome() == null ? "" : ctx.getNome()
            ), ctx, CodigoNegocio.SEM_AGENDAMENTO);
        }
        return evento("RESULTADO_SUCESSO_LISTA", Map.of("lista", Formatador.agendamentos(itens)), ctx, CodigoNegocio.SUCESSO);
    }

    private ResultadoOperacao escolher(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        HorarioOferta horario = horarioEscolhido(command);
        if (horario == null) {
            return evento("RESULTADO_HORARIO_FORA_DA_OFERTA", Map.of(), ctx, CodigoNegocio.HORARIO_FORA_DA_OFERTA);
        }
        preencherConfirmacao(ctx, horario, "agendar");
        String resumo = resumo(ctx, horario);
        String introducao = ctx.getNome() == null || ctx.getNome().isBlank()
                ? "Confirme os dados do agendamento:"
                : Textos.primeiroNome(ctx.getNome()) + ", confirme os dados do agendamento:";
        return evento("RESULTADO_CONFIRMACAO_AUSENTE", Map.of(
                "resumo", resumo,
                "texto", introducao + "\n\n" + resumo
                        + "\n\nPosso confirmar? Responda sim ou não.",
                "acao", "agendar"
        ), ctx, CodigoNegocio.CONFIRMACAO_AUSENTE);
    }

    private ResultadoOperacao agendar(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temIdentidadeAgendamento()) {
            return naoIdentificado(ctx);
        }
        if (ctx.temConfirmacao()) {
            String tipo = ctx.getConfirmacaoTipo();
            if ("cancelar".equals(tipo) || "confirmar".equals(tipo)) {
                if (Textos.nao(falaConfirmacao(command))) {
                    limparConfirmacao(ctx);
                    if (!ctx.getAgendamentos().isEmpty()) {
                        return evento(
                                "RESULTADO_SUCESSO_LISTA",
                                Map.of("lista", Formatador.agendamentos(ctx.getAgendamentos())),
                                ctx,
                                CodigoNegocio.SUCESSO
                        );
                    }
                    return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
                }
                return alterarExistente(command, tipo);
            }
        }
        if (ctx.getAgendamentoAtualId() != null && ctx.temConfirmacao()) {
            return remarcar(command);
        }
        if (!ctx.temConfirmacao() || (ctx.getConfirmacaoTipo() != null && !"agendar".equals(ctx.getConfirmacaoTipo()))) {
            return confirmacaoAusente(ctx, "agendar");
        }
        String fala = falaConfirmacao(command);
        if (Textos.nao(fala)) {
            limparConfirmacao(ctx);
            return evento("RESULTADO_LISTA_HORARIOS", Map.of(
                    "lista", Formatador.horarios(ctx.getEspecialidadeNome(), ctx.getPeriodo(), ctx.getOferta())
            ), ctx, CodigoNegocio.SUCESSO);
        }
        if (!Textos.sim(fala)) {
            return confirmacaoAusente(ctx, "agendar");
        }
        if (ctx.getOferta().stream().noneMatch(item -> item.nrSequencia().equals(ctx.getConfirmacaoNr()))) {
            return evento("RESULTADO_HORARIO_FORA_DA_OFERTA", Map.of(), ctx, CodigoNegocio.HORARIO_FORA_DA_OFERTA);
        }
        IdentidadePaciente identidade = ctx.isSemCadastro()
                ? new IdentidadePaciente(ctx.getCpf(), ctx.getNascimento(), ctx.getNome())
                : identidades.buscar(ctx.getPacienteId());
        if (identidade == null) {
            return naoIdentificado(ctx);
        }
        ProcedimentoExame exame = null;
        if (ctx.isExame()) {
            exame = agenda.procedimentoExame(ctx.getExameId());
            if (exame == null) {
                return indisponivel(ctx);
            }
        }
        ResultadoEscrita gravacao = escrita.agendar(new PedidoAgendar(
                chave(command, ctx.getConfirmacaoNr()),
                ctx.getPacienteId(),
                ctx.getConfirmacaoCdAgenda(),
                ctx.getConfirmacaoDataHora(),
                identidade.cpf(),
                identidade.nascimento(),
                identidade.nomeCompleto(),
                ctx.isSemCadastro(),
                exame,
                ctx.getConvenioId()
        ));
        return resultadoEscrita(ctx, gravacao.codigo(), "agendar");
    }

    private ResultadoOperacao alterarExistente(FulfillmentCommand command, String tipo) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temPaciente()) {
            return naoIdentificado(ctx);
        }
        if (ctx.getAgendamentos().isEmpty()) {
            return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        if (ctx.temConfirmacao() && tipo.equals(ctx.getConfirmacaoTipo()) && Textos.sim(falaConfirmacao(command))) {
            AgendamentoAberto item = ctx.getAgendamentos().stream()
                    .filter(agendamento -> agendamento.nrSequencia().equals(ctx.getConfirmacaoNr()))
                    .findFirst()
                    .orElse(null);
            if (item == null) {
                return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
            }
            String chave = chave(command, item.nrSequencia());
            ResultadoEscrita gravacao = "cancelar".equals(tipo)
                    ? escrita.cancelar(chave, ctx.getPacienteId(), item.nrSequencia())
                    : escrita.confirmar(chave, ctx.getPacienteId(), item.nrSequencia());
            return resultadoEscrita(ctx, gravacao.codigo(), tipo);
        }
        AgendamentoAberto item = agendamentoEscolhido(command);
        if (item == null) {
            return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        ctx.setConfirmacaoNr(item.nrSequencia());
        ctx.setConfirmacaoTipo(tipo);
        ctx.setConfirmacaoDataHora(item.dataHora());
        ctx.setConfirmacaoEspecialidade(item.especialidade());
        ctx.setConfirmacaoMedico(item.medico());
        ctx.setLimparConfirmacao(false);
        String pergunta = "cancelar".equals(tipo)
                ? "Posso cancelar a consulta de " + Textos.dia(item.dataHora()) + "?"
                : "Posso confirmar a consulta de " + Textos.dia(item.dataHora()) + "?";
        return evento("RESULTADO_CONFIRMACAO_AUSENTE", Map.of(
                "resumo", Textos.dia(item.dataHora()),
                "texto", pergunta,
                "acao", tipo
        ), ctx, CodigoNegocio.CONFIRMACAO_AUSENTE);
    }

    private ResultadoOperacao remarcar(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        if (!ctx.temPaciente()) {
            return naoIdentificado(ctx);
        }
        if (!ctx.temConfirmacao() || !"agendar".equals(ctx.getConfirmacaoTipo()) && !"remarcar".equals(ctx.getConfirmacaoTipo())) {
            AgendamentoAberto atual = agendamentoEscolhido(command);
            if (atual == null && ctx.getAgendamentoAtualId() == null) {
                return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
            }
            if (atual != null) {
                ctx.setAgendamentoAtualId(atual.nrSequencia());
            }
            return evento("RESULTADO_PERIODO", Map.of(), ctx, CodigoNegocio.SUCESSO);
        }
        if (!Textos.sim(falaConfirmacao(command))) {
            return confirmacaoAusente(ctx, "remarcar");
        }
        if (ctx.getAgendamentoAtualId() == null || ctx.getAgendamentos().stream().noneMatch(item -> item.nrSequencia().equals(ctx.getAgendamentoAtualId()))) {
            return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO);
        }
        if (ctx.getOferta().stream().noneMatch(item -> item.nrSequencia().equals(ctx.getConfirmacaoNr()))) {
            return evento("RESULTADO_HORARIO_FORA_DA_OFERTA", Map.of(), ctx, CodigoNegocio.HORARIO_FORA_DA_OFERTA);
        }
        IdentidadePaciente identidade = identidades.buscar(ctx.getPacienteId());
        if (identidade == null) {
            return naoIdentificado(ctx);
        }
        ResultadoEscrita gravacao = escrita.remarcar(
                chave(command, ctx.getAgendamentoAtualId() + ":" + ctx.getConfirmacaoNr()),
                ctx.getPacienteId(),
                ctx.getAgendamentoAtualId(),
                new PedidoAgendar(
                        chave(command, ctx.getConfirmacaoNr()),
                        ctx.getPacienteId(),
                        ctx.getConfirmacaoCdAgenda(),
                        ctx.getConfirmacaoDataHora(),
                        identidade.cpf(),
                        identidade.nascimento(),
                        identidade.nomeCompleto(),
                        false,
                        null,
                        ctx.getConvenioId()
                )
        );
        return resultadoEscrita(ctx, gravacao.codigo(), "remarcar");
    }

    private void aplicarFiltros(FulfillmentCommand command) {
        ContextoConversa ctx = command.contexto();
        String periodo = Textos.periodo(Textos.texto(command.parameters(), "periodo"));
        if (periodo.isBlank()) {
            periodo = Textos.periodo(command.queryText());
        }
        if (!periodo.isBlank()) {
            ctx.setPeriodo(periodo);
        }
        if (Textos.semana(command.queryText()) || Textos.semana(Textos.texto(command.parameters(), "data"))) {
            ctx.setSemana(true);
            if (ctx.getDia() == null) {
                ctx.setDia(LocalDate.now());
            }
            return;
        }
        LocalDate dia = Textos.data(command.parameters().get("data"));
        if (dia == null) {
            dia = Textos.data(command.queryText());
        }
        if (dia != null) {
            ctx.setDia(dia);
            ctx.setSemana(false);
        }
    }

    private HorarioOferta horarioEscolhido(FulfillmentCommand command) {
        List<HorarioOferta> oferta = command.contexto().getOferta();
        if (oferta == null || oferta.isEmpty()) {
            return null;
        }
        Integer numero = Textos.numero(command.parameters(), "escolha");
        if (numero == null) {
            numero = Textos.numero(Map.of("n", command.queryText() == null ? "" : command.queryText()), "n");
        }
        if (numero == null || numero < 1 || numero > oferta.size()) {
            return null;
        }
        return oferta.get(numero - 1);
    }

    private AgendamentoAberto agendamentoEscolhido(FulfillmentCommand command) {
        List<AgendamentoAberto> itens = command.contexto().getAgendamentos();
        Integer numero = Textos.numero(command.parameters(), "escolha");
        if (numero == null) {
            numero = Textos.numero(Map.of("n", command.queryText() == null ? "" : command.queryText()), "n");
        }
        if (numero == null || numero < 1 || numero > itens.size()) {
            return null;
        }
        return itens.get(numero - 1);
    }

    private void preencherConfirmacao(ContextoConversa ctx, HorarioOferta horario, String tipo) {
        ctx.setConfirmacaoNr(horario.nrSequencia());
        ctx.setConfirmacaoCdAgenda(horario.cdAgenda());
        ctx.setConfirmacaoDataHora(horario.dataHora());
        if (ctx.isExame()) {
            ctx.setConfirmacaoEspecialidade(ctx.getExameNome());
        } else {
            ctx.setConfirmacaoEspecialidade(horario.especialidade() == null ? ctx.getEspecialidadeNome() : horario.especialidade());
        }
        ctx.setConfirmacaoMedico(horario.medico());
        ctx.setConfirmacaoLocal(horario.local());
        ctx.setConfirmacaoTipo(tipo);
        ctx.setConfirmacaoResumo(resumo(ctx, horario));
        ctx.setLimparConfirmacao(false);
    }

    private ResultadoOperacao resultadoEscrita(ContextoConversa ctx, CodigoNegocio codigo, String acao) {
        if (codigo == CodigoNegocio.HORARIO_INDISPONIVEL) {
            return evento("RESULTADO_HORARIO_INDISPONIVEL", Map.of(), ctx, codigo);
        }
        if (codigo == CodigoNegocio.AGENDAMENTO_NAO_ENCONTRADO) {
            return evento("RESULTADO_AGENDAMENTO_NAO_ENCONTRADO", Map.of(), ctx, codigo);
        }
        if (codigo != CodigoNegocio.SUCESSO) {
            return indisponivel(ctx);
        }
        limparConfirmacao(ctx);
        String data = Textos.diaResumo(ctx.getConfirmacaoDataHora());
        String hora = Textos.hora(ctx.getConfirmacaoDataHora());
        String texto;
        String orientacao = "";
        String fechamento = "";
        if ("cancelar".equals(acao)) {
            texto = Formatador.textoCancelado(Textos.dia(ctx.getConfirmacaoDataHora()), hora);
        } else if (ctx.isExame()) {
            texto = Formatador.textoExameMarcado(
                    ctx.getNome(),
                    ctx.getConfirmacaoEspecialidade(),
                    data,
                    hora,
                    ctx.getConfirmacaoLocal(),
                    ctx.getConvenioNome()
            );
            orientacao = Formatador.orientacoesExame(
                    ctx.getConfirmacaoEspecialidade(),
                    preparoExame(ctx.getExameId()),
                    OrientacoesExame.gerais()
            );
            fechamento = Formatador.fechamentoExame();
        } else {
            texto = Formatador.textoMarcado(
                    ctx.getNome(),
                    ctx.getConfirmacaoEspecialidade(),
                    data,
                    hora,
                    ctx.getConfirmacaoMedico(),
                    ctx.getConvenioNome()
            );
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("especialidade", ctx.getConfirmacaoEspecialidade() == null ? "" : ctx.getConfirmacaoEspecialidade());
        params.put("data", "cancelar".equals(acao) ? Textos.dia(ctx.getConfirmacaoDataHora()) : data);
        params.put("hora", hora);
        params.put("medico", ctx.getConfirmacaoMedico() == null ? "" : ctx.getConfirmacaoMedico());
        params.put("convenio", ctx.getConvenioNome() == null ? "" : ctx.getConvenioNome());
        params.put("acao", acao);
        params.put("texto", texto);
        params.put("orientacao", orientacao);
        params.put("fechamento", fechamento);
        return evento("RESULTADO_SUCESSO_ESCRITA", params, ctx, CodigoNegocio.SUCESSO);
    }

    private String preparoExame(String exameId) {
        if (exameId == null || exameId.isBlank()) {
            return "";
        }
        try {
            String preparo = agenda.orientacaoExame(exameId);
            return preparo == null ? "" : preparo;
        } catch (RuntimeException ex) {
            // O exame já foi gravado; a falta do preparo não pode virar erro para o paciente.
            LOG.warn("orientacao_exame_indisponivel exame={}", exameId, ex);
            return "";
        }
    }

    private ResultadoOperacao confirmacaoAusente(ContextoConversa ctx, String acao) {
        String resumo = ctx.getConfirmacaoResumo() == null ? "" : ctx.getConfirmacaoResumo();
        String introducao = ctx.getNome() == null || ctx.getNome().isBlank()
                ? "Confirme os dados do agendamento:"
                : Textos.primeiroNome(ctx.getNome()) + ", confirme os dados do agendamento:";
        String texto = "cancelar".equals(acao)
                ? "Posso cancelar a consulta de " + resumo + "?"
                : introducao + "\n\n" + resumo
                        + "\n\nPosso confirmar? Responda sim ou não.";
        return evento("RESULTADO_CONFIRMACAO_AUSENTE", Map.of("resumo", resumo, "texto", texto, "acao", acao), ctx, CodigoNegocio.CONFIRMACAO_AUSENTE);
    }

    private ResultadoOperacao falarPagina(ContextoConversa ctx, PaginaLista pagina) {
        ctx.setLista(pagina);
        String evento = switch (pagina.tipo()) {
            case "convenio" -> "RESULTADO_LISTA_CONVENIO";
            case "profissional" -> "RESULTADO_LISTA_PROFISSIONAL";
            case "especialidade" -> "RESULTADO_LISTA_ESPECIALIDADE";
            case "exame" -> "RESULTADO_LISTA_EXAME";
            default -> "RESULTADO_SUCESSO_LISTA";
        };
        Map<String, String> params = new LinkedHashMap<>();
        params.put("lista", Formatador.opcoes(pagina.exibidos()));
        if ("profissional".equals(pagina.tipo()) || "especialidade".equals(pagina.tipo())) {
            params.put("medico", ctx.getMedicoNome() == null ? "" : ctx.getMedicoNome());
        }
        return evento(evento, params, ctx, CodigoNegocio.SUCESSO);
    }

    private void limparConfirmacao(ContextoConversa ctx) {
        ctx.setLimparConfirmacao(true);
        ctx.setConfirmacaoNr(null);
        ctx.setConfirmacaoTipo(null);
        ctx.setConfirmacaoResumo(null);
    }

    private String falaConfirmacao(FulfillmentCommand command) {
        String fala = Textos.texto(command.parameters(), "confirmacao");
        if (fala.isBlank()) {
            fala = command.queryText() == null ? "" : command.queryText();
        }
        return fala;
    }

    private String chave(FulfillmentCommand command, String id) {
        return Textos.conversationId(command.session()) + ":" + command.intent() + ":" + id;
    }

    private ResultadoOperacao naoIdentificado(ContextoConversa ctx) {
        return evento("RESULTADO_NAO_IDENTIFICADO", Map.of(), ctx, CodigoNegocio.NAO_IDENTIFICADO);
    }

    private ResultadoOperacao indisponivel(ContextoConversa ctx) {
        return evento("RESULTADO_INDISPONIVEL", Map.of(), ctx, CodigoNegocio.INDISPONIVEL);
    }

    private ResultadoOperacao evento(String nome, Map<String, String> parametros, ContextoConversa ctx, CodigoNegocio codigo) {
        return new ResultadoOperacao(nome, parametros, ctx, codigo);
    }
}
