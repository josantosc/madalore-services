package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ContextoConversa {

    private String pacienteId;
    private String nome;
    private String cpf;
    private LocalDate nascimento;
    private boolean semCadastro;
    private String convenioId;
    private String convenioNome;
    private String medicoId;
    private String medicoNome;
    private String especialidadeId;
    private String especialidadeNome;
    private String periodo;
    private LocalDate dia;
    private boolean semana;
    private List<HorarioOferta> oferta = new ArrayList<>();
    private List<AgendamentoAberto> agendamentos = new ArrayList<>();
    private String confirmacaoNr;
    private String confirmacaoResumo;
    private String confirmacaoCdAgenda;
    private LocalDateTime confirmacaoDataHora;
    private String confirmacaoEspecialidade;
    private String confirmacaoMedico;
    private String confirmacaoLocal;
    private String confirmacaoTipo;
    private String agendamentoAtualId;
    private PaginaLista lista;
    private Integer quantidadeFutura;
    private String quandoProximo;
    private boolean limparConfirmacao;
    private boolean limparProfissional;
    private boolean exame;
    private String exameId;
    private String exameNome;

    public boolean temPaciente() {
        return pacienteId != null && !pacienteId.isBlank();
    }

    public boolean temIdentidadeAgendamento() {
        return temPaciente()
                || semCadastro
                && nome != null
                && !nome.isBlank()
                && nascimento != null;
    }

    public boolean temConfirmacao() {
        return confirmacaoNr != null && !confirmacaoNr.isBlank();
    }

    public String getPacienteId() {
        return pacienteId;
    }

    public void setPacienteId(String pacienteId) {
        this.pacienteId = pacienteId;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getCpf() {
        return cpf;
    }

    public void setCpf(String cpf) {
        this.cpf = cpf;
    }

    public LocalDate getNascimento() {
        return nascimento;
    }

    public void setNascimento(LocalDate nascimento) {
        this.nascimento = nascimento;
    }

    public boolean isSemCadastro() {
        return semCadastro;
    }

    public void setSemCadastro(boolean semCadastro) {
        this.semCadastro = semCadastro;
    }

    public String getConvenioId() {
        return convenioId;
    }

    public void setConvenioId(String convenioId) {
        this.convenioId = convenioId;
    }

    public String getConvenioNome() {
        return convenioNome;
    }

    public void setConvenioNome(String convenioNome) {
        this.convenioNome = convenioNome;
    }

    public String getMedicoId() {
        return medicoId;
    }

    public void setMedicoId(String medicoId) {
        this.medicoId = medicoId;
    }

    public String getMedicoNome() {
        return medicoNome;
    }

    public void setMedicoNome(String medicoNome) {
        this.medicoNome = medicoNome;
    }

    public String getEspecialidadeId() {
        return especialidadeId;
    }

    public void setEspecialidadeId(String especialidadeId) {
        this.especialidadeId = especialidadeId;
    }

    public String getEspecialidadeNome() {
        return especialidadeNome;
    }

    public void setEspecialidadeNome(String especialidadeNome) {
        this.especialidadeNome = especialidadeNome;
    }

    public String getPeriodo() {
        return periodo;
    }

    public void setPeriodo(String periodo) {
        this.periodo = periodo;
    }

    public LocalDate getDia() {
        return dia;
    }

    public void setDia(LocalDate dia) {
        this.dia = dia;
    }

    public boolean isSemana() {
        return semana;
    }

    public void setSemana(boolean semana) {
        this.semana = semana;
    }

    public List<HorarioOferta> getOferta() {
        return oferta;
    }

    public void setOferta(List<HorarioOferta> oferta) {
        this.oferta = oferta == null ? new ArrayList<>() : oferta;
    }

    public List<AgendamentoAberto> getAgendamentos() {
        return agendamentos;
    }

    public void setAgendamentos(List<AgendamentoAberto> agendamentos) {
        this.agendamentos = agendamentos == null ? new ArrayList<>() : agendamentos;
    }

    public String getConfirmacaoNr() {
        return confirmacaoNr;
    }

    public void setConfirmacaoNr(String confirmacaoNr) {
        this.confirmacaoNr = confirmacaoNr;
    }

    public String getConfirmacaoResumo() {
        return confirmacaoResumo;
    }

    public void setConfirmacaoResumo(String confirmacaoResumo) {
        this.confirmacaoResumo = confirmacaoResumo;
    }

    public String getConfirmacaoCdAgenda() {
        return confirmacaoCdAgenda;
    }

    public void setConfirmacaoCdAgenda(String confirmacaoCdAgenda) {
        this.confirmacaoCdAgenda = confirmacaoCdAgenda;
    }

    public LocalDateTime getConfirmacaoDataHora() {
        return confirmacaoDataHora;
    }

    public void setConfirmacaoDataHora(LocalDateTime confirmacaoDataHora) {
        this.confirmacaoDataHora = confirmacaoDataHora;
    }

    public String getConfirmacaoEspecialidade() {
        return confirmacaoEspecialidade;
    }

    public void setConfirmacaoEspecialidade(String confirmacaoEspecialidade) {
        this.confirmacaoEspecialidade = confirmacaoEspecialidade;
    }

    public String getConfirmacaoMedico() {
        return confirmacaoMedico;
    }

    public void setConfirmacaoMedico(String confirmacaoMedico) {
        this.confirmacaoMedico = confirmacaoMedico;
    }

    public String getConfirmacaoLocal() {
        return confirmacaoLocal;
    }

    public void setConfirmacaoLocal(String confirmacaoLocal) {
        this.confirmacaoLocal = confirmacaoLocal;
    }

    public String getConfirmacaoTipo() {
        return confirmacaoTipo;
    }

    public void setConfirmacaoTipo(String confirmacaoTipo) {
        this.confirmacaoTipo = confirmacaoTipo;
    }

    public String getAgendamentoAtualId() {
        return agendamentoAtualId;
    }

    public void setAgendamentoAtualId(String agendamentoAtualId) {
        this.agendamentoAtualId = agendamentoAtualId;
    }

    public PaginaLista getLista() {
        return lista;
    }

    public void setLista(PaginaLista lista) {
        this.lista = lista;
    }

    public Integer getQuantidadeFutura() {
        return quantidadeFutura;
    }

    public void setQuantidadeFutura(Integer quantidadeFutura) {
        this.quantidadeFutura = quantidadeFutura;
    }

    public String getQuandoProximo() {
        return quandoProximo;
    }

    public void setQuandoProximo(String quandoProximo) {
        this.quandoProximo = quandoProximo;
    }

    public boolean isLimparConfirmacao() {
        return limparConfirmacao;
    }

    public void setLimparConfirmacao(boolean limparConfirmacao) {
        this.limparConfirmacao = limparConfirmacao;
    }

    public boolean isLimparProfissional() {
        return limparProfissional;
    }

    public void setLimparProfissional(boolean limparProfissional) {
        this.limparProfissional = limparProfissional;
    }

    public boolean isExame() {
        return exame;
    }

    public void setExame(boolean exame) {
        this.exame = exame;
    }

    public String getExameId() {
        return exameId;
    }

    public void setExameId(String exameId) {
        this.exameId = exameId;
    }

    public String getExameNome() {
        return exameNome;
    }

    public void setExameNome(String exameNome) {
        this.exameNome = exameNome;
    }
}
