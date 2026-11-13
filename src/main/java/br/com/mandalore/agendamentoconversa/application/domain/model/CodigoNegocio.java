package br.com.mandalore.agendamentoconversa.application.domain.model;

public enum CodigoNegocio {
    PACIENTE_NAO_ENCONTRADO("paciente_nao_encontrado"),
    NAO_IDENTIFICADO("nao_identificado"),
    SEM_HORARIO("sem_horario"),
    HORARIO_INDISPONIVEL("horario_indisponivel"),
    HORARIO_FORA_DA_OFERTA("horario_fora_da_oferta"),
    CONFIRMACAO_AUSENTE("confirmacao_ausente"),
    AGENDAMENTO_NAO_ENCONTRADO("agendamento_nao_encontrado"),
    AGENDAMENTO_EM_ATENDIMENTO("agendamento_em_atendimento"),
    SUCESSO("sucesso"),
    INDISPONIVEL("indisponivel"),
    SEM_AGENDAMENTO("sem_agendamento"),
    JA_TEM_AGENDAMENTO("ja_tem_agendamento"),
    FAQ_SEM_RESPOSTA("faq_sem_resposta"),
    CHEGADA_FORA_DO_DIA("chegada_fora_do_dia"),
    STATUS_INVALIDO_PARA_CHEGADA("status_invalido_para_chegada"),
    ATENDIMENTO_EM_ANDAMENTO("atendimento_em_andamento"),
    STATUS_INVALIDO_PARA_CONSULTA("status_invalido_para_consulta"),
    PROFISSIONAL_EM_CONSULTA("profissional_em_consulta"),
    ATENDIMENTO_NAO_EM_CONSULTA("atendimento_nao_em_consulta"),
    REGISTRO_JA_LIBERADO("registro_ja_liberado"),
    REGISTRO_NAO_LIBERADO("registro_nao_liberado"),
    PROFISSIONAL_SEM_CONSELHO("profissional_sem_conselho"),
    SEM_REGISTRO_CLINICO("sem_registro_clinico"),
    RASCUNHO_PENDENTE("rascunho_pendente"),
    DIAGNOSTICO_PRINCIPAL_JA_EXISTE("diagnostico_principal_ja_existe"),
    DIAGNOSTICO_DUPLICADO("diagnostico_duplicado"),
    ALERGIA_DUPLICADA("alergia_duplicada"),
    STATUS_INVALIDO_PARA_CHAMADA("status_invalido_para_chamada"),
    STATUS_INVALIDO_PARA_FALTA("status_invalido_para_falta"),
    FALTA_ANTES_DO_HORARIO("falta_antes_do_horario"),
    ATALHO_DUPLICADO("atalho_duplicado"),
    MEDICAMENTO_DUPLICADO("medicamento_duplicado"),
    NAO_ENCONTRADO("nao_encontrado");

    private final String valor;

    CodigoNegocio(String valor) {
        this.valor = valor;
    }

    public String valor() {
        return valor;
    }
}
