package br.com.mandalore.agendamentoconversa.application.domain.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import br.com.mandalore.agendamentoconversa.application.domain.port.in.MedicamentoRotinaUseCase;
import br.com.mandalore.agendamentoconversa.application.domain.port.out.MedicamentoRotinaPort;

/**
 * Medicamentos de rotina da clínica. A posologia é sugestão: o médico revisa antes de liberar a receita.
 */
@Service
@ConditionalOnProperty(name = {"atendimento.enabled", "observability.postgres.enabled"}, havingValue = "true")
public class MedicamentoRotinaService implements MedicamentoRotinaUseCase {

    static final String LISTA_PADRAO = "db/atendimento/medicamentos-rotina.csv";
    static final int MEDICAMENTO_MAX = 300;
    static final int APRESENTACAO_MAX = 200;
    static final int POSOLOGIA_MAX = 500;
    static final int VIA_MAX = 60;

    private final MedicamentoRotinaPort port;
    private final HabilitacaoAtendimento habilitacao;
    private final List<Atendimento.MedicamentoRotina> padrao;

    @Autowired
    public MedicamentoRotinaService(MedicamentoRotinaPort port, HabilitacaoAtendimento habilitacao) {
        this(port, habilitacao, listaPadrao());
    }

    MedicamentoRotinaService(MedicamentoRotinaPort port, HabilitacaoAtendimento habilitacao,
                             List<Atendimento.MedicamentoRotina> padrao) {
        this.port = port;
        this.habilitacao = habilitacao;
        this.padrao = List.copyOf(padrao);
    }

    @Override
    public List<Atendimento.MedicamentoRotina> listar(String conta) {
        habilitacao.exigir(conta);
        port.semearSeNova(conta, padrao);
        return port.listar(conta);
    }

    @Override
    public Atendimento.Operacao salvar(String conta, Atendimento.MedicamentoRotina item, String ator) {
        habilitacao.exigir(conta);
        return port.salvar(conta, normalizado(item), ator);
    }

    @Override
    public boolean excluir(String conta, long id) {
        habilitacao.exigir(conta);
        return port.excluir(conta, id);
    }

    @Override
    public int restaurarPadrao(String conta, String ator) {
        habilitacao.exigir(conta);
        return port.incluirFaltantes(conta, padrao, ator);
    }

    static Atendimento.MedicamentoRotina normalizado(Atendimento.MedicamentoRotina in) {
        if (in == null) {
            throw new IllegalArgumentException("Informe o medicamento");
        }
        String medicamento = texto(in.medicamento(), MEDICAMENTO_MAX, "Medicamento");
        if (medicamento.isEmpty()) {
            throw new IllegalArgumentException("Informe o medicamento");
        }
        return new Atendimento.MedicamentoRotina(in.id(), medicamento,
                texto(in.apresentacao(), APRESENTACAO_MAX, "Apresentação"),
                texto(in.posologia(), POSOLOGIA_MAX, "Posologia"),
                texto(in.via(), VIA_MAX, "Via"), in.usoContinuo());
    }

    private static String texto(String valor, int max, String campo) {
        String limpo = valor == null ? "" : valor.strip().replaceAll("\\s+", " ");
        if (limpo.length() > max) {
            throw new IllegalArgumentException(campo + " deve ter no máximo " + max + " caracteres");
        }
        return limpo;
    }

    /** Colunas: medicamento;apresentacao;posologia;via;uso_continuo (S/N), com cabeçalho. */
    static List<Atendimento.MedicamentoRotina> lerCsv(InputStream entrada) throws IOException {
        try (BufferedReader leitor = new BufferedReader(new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
            return leitor.lines()
                    .skip(1)
                    .filter(linha -> !linha.isBlank())
                    .map(linha -> {
                        String[] c = linha.split(";", -1);
                        if (c.length != 5) {
                            throw new IllegalStateException("Linha inválida na lista padrão de medicamentos: " + linha);
                        }
                        return normalizado(new Atendimento.MedicamentoRotina(null, c[0], c[1], c[2], c[3],
                                "S".equalsIgnoreCase(c[4].strip())));
                    })
                    .toList();
        }
    }

    static List<Atendimento.MedicamentoRotina> listaPadrao() {
        try (InputStream entrada = new ClassPathResource(LISTA_PADRAO).getInputStream()) {
            return lerCsv(entrada);
        } catch (IOException ex) {
            throw new UncheckedIOException("Não foi possível ler " + LISTA_PADRAO, ex);
        }
    }
}
