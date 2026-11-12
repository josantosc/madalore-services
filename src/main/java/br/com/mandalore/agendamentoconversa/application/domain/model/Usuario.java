package br.com.mandalore.agendamentoconversa.application.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Usuários da central de administração, guardados no PostgreSQL. */
public final class Usuario {

    /** Telas da central, pelo nome do módulo ({@code Modulo} no adaptador web). */
    public static final List<String> TELAS = List.of("CURADORIA", "PRECOS", "SIMULADOR", "AGENDA", "AGENDA_CADASTROS",
            "FUNIL", "USUARIOS", "ATENDIMENTO", "CONSULTORIO");

    private Usuario() {
    }

    public enum Perfil {
        ADMINISTRADOR("Administrador geral", TELAS),
        GESTOR("Gestor da clínica", List.of("AGENDA", "AGENDA_CADASTROS", "FUNIL", "PRECOS", "USUARIOS",
                "ATENDIMENTO")),
        RECEPCAO("Recepção", List.of("AGENDA", "FUNIL", "ATENDIMENTO")),
        MEDICO("Médico", List.of("ATENDIMENTO", "CONSULTORIO")),
        CADASTRO_AGENDA("Cadastros da agenda", List.of("AGENDA_CADASTROS")),
        FATURAMENTO("Faturamento", List.of("FUNIL", "PRECOS")),
        CURADORIA("Curadoria", List.of("CURADORIA", "SIMULADOR"));

        private final String titulo;
        private final List<String> telas;

        Perfil(String titulo, List<String> telas) {
            this.titulo = titulo;
            this.telas = telas;
        }

        public String titulo() {
            return titulo;
        }

        public List<String> telas() {
            return telas;
        }
    }

    /**
     * {@code contas} vazio só no administrador geral (todas as clínicas). {@code telasExtras} e
     * {@code telasRetiradas} ajustam as telas do perfil para esta pessoa.
     */
    public record Cadastro(
            long id,
            String nome,
            String email,
            Perfil perfil,
            boolean ativo,
            boolean trocarSenha,
            Set<String> contas,
            Set<String> telasExtras,
            Set<String> telasRetiradas,
            int versao,
            String criadoPor,
            Instant criadoEm,
            Instant ultimoAcessoEm
    ) {
        public Cadastro {
            contas = contas == null ? Set.of() : Set.copyOf(contas);
            telasExtras = telasExtras == null ? Set.of() : Set.copyOf(telasExtras);
            telasRetiradas = telasRetiradas == null ? Set.of() : Set.copyOf(telasRetiradas);
        }

        public Set<String> telas() {
            Set<String> telas = new LinkedHashSet<>(perfil.telas());
            telas.addAll(telasExtras);
            telas.removeAll(telasRetiradas);
            return Set.copyOf(telas);
        }
    }

    public record ComSenha(Cadastro cadastro, String senhaHash) {
    }

    /** Dados que o administrador preenche ao criar ou editar. */
    public record Dados(String nome, String email, Perfil perfil, Set<String> contas, Set<String> telasExtras,
                        Set<String> telasRetiradas) {
        public Dados {
            contas = contas == null ? Set.of() : Set.copyOf(contas);
            telasExtras = telasExtras == null ? Set.of() : Set.copyOf(telasExtras);
            telasRetiradas = telasRetiradas == null ? Set.of() : Set.copyOf(telasRetiradas);
        }
    }

    /** Quem está fazendo a alteração. {@code geral}: administrador geral, sem limite de clínica. */
    public record Ator(String usuario, Long id, boolean geral, Set<String> contas, Set<String> telas) {
        public Ator {
            contas = contas == null ? Set.of() : Set.copyOf(contas);
            telas = telas == null ? Set.of() : Set.copyOf(telas);
        }
    }

    public enum TipoEvento {
        LOGIN, LOGIN_RECUSADO, SENHA_TROCADA, SENHA_RESETADA, USUARIO_CRIADO, USUARIO_ALTERADO, USUARIO_DESATIVADO,
        USUARIO_REATIVADO
    }

    public record Evento(Instant quando, TipoEvento tipo, String alvo, String autor, String ip) {
    }
}
