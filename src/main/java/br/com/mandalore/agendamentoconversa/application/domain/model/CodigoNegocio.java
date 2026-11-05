package br.com.mandalore.agendamentoconversa.application.domain.model;

public enum CodigoNegocio {
    PACIENTE_NAO_ENCONTRADO("paciente_nao_encontrado"),
    NAO_IDENTIFICADO("nao_identificado"),
    SEM_HORARIO("sem_horario"),
    HORARIO_INDISPONIVEL("horario_indisponivel"),
    HORARIO_FORA_DA_OFERTA("horario_fora_da_oferta"),
    CONFIRMACAO_AUSENTE("confirmacao_ausente"),
    AGENDAMENTO_NAO_ENCONTRADO("agendamento_nao_encontrado"),
    SUCESSO("sucesso"),
    INDISPONIVEL("indisponivel"),
    SEM_AGENDAMENTO("sem_agendamento"),
    JA_TEM_AGENDAMENTO("ja_tem_agendamento"),
    FAQ_SEM_RESPOSTA("faq_sem_resposta");

    private final String valor;

    CodigoNegocio(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }
}
