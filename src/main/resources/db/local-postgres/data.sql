SET search_path TO tasy_test;

TRUNCATE TABLE agendamento_operacao;

INSERT INTO pessoa_fisica (
    cd_pessoa_fisica, ie_tipo_pessoa, nm_pessoa_fisica, dt_nascimento,
    nr_cpf, nm_usuario, dt_atualizacao
) VALUES
    ('1001', 2, 'Paciente Teste', TIMESTAMP '1990-05-20 00:00:00',
     '529.982.247-25', 'LOCAL', CURRENT_TIMESTAMP),
    ('2001', 1, 'Dra. Ana Teste', TIMESTAMP '1980-03-10 00:00:00',
     '111.444.777-35', 'LOCAL', CURRENT_TIMESTAMP),
    ('2002', 1, 'Dr. Bruno Teste', TIMESTAMP '1975-08-15 00:00:00',
     '123.456.789-09', 'LOCAL', CURRENT_TIMESTAMP)
ON CONFLICT (cd_pessoa_fisica) DO UPDATE SET
    nm_pessoa_fisica = EXCLUDED.nm_pessoa_fisica,
    dt_nascimento = EXCLUDED.dt_nascimento,
    nr_cpf = EXCLUDED.nr_cpf;

INSERT INTO especialidade_medica (
    cd_especialidade, ds_especialidade, ie_situacao
) VALUES
    (10, 'Clínica Médica', 'A'),
    (20, 'Cardiologia', 'A'),
    (30, 'Diagnóstico por Imagem', 'A')
ON CONFLICT (cd_especialidade) DO UPDATE SET
    ds_especialidade = EXCLUDED.ds_especialidade,
    ie_situacao = EXCLUDED.ie_situacao;

INSERT INTO convenio (cd_convenio, ds_convenio, ie_situacao)
VALUES
    (1, 'Particular', 'A'),
    (15, 'Convênio Teste', 'A')
ON CONFLICT (cd_convenio) DO UPDATE SET
    ds_convenio = EXCLUDED.ds_convenio,
    ie_situacao = EXCLUDED.ie_situacao;

INSERT INTO agenda (
    cd_agenda, ds_agenda, cd_tipo_agenda, cd_pessoa_fisica,
    cd_especialidade, cd_agenda_externa, ie_situacao
) VALUES
    (3001, 'Consultório 1', 3, '2001', 10, 'CONS-ANA', 'A'),
    (3002, 'Consultório 2', 3, '2002', 20, 'CONS-BRUNO', 'A'),
    (4001, 'Sala de Exames 1', 2, '2001', 30, 'EXAME-IMG', 'A')
ON CONFLICT (cd_agenda) DO UPDATE SET
    ds_agenda = EXCLUDED.ds_agenda,
    cd_tipo_agenda = EXCLUDED.cd_tipo_agenda,
    cd_pessoa_fisica = EXCLUDED.cd_pessoa_fisica,
    cd_especialidade = EXCLUDED.cd_especialidade,
    cd_agenda_externa = EXCLUDED.cd_agenda_externa,
    ie_situacao = EXCLUDED.ie_situacao;

INSERT INTO proc_interno (
    nr_sequencia, ds_proc_exame, cd_procedimento, ie_origem_proced,
    cd_integracao, ie_tipo, ie_situacao
) VALUES
    (20, 'Cirurgia Prueba Equipo Digisystem', 108, 4, NULL, 'O', 'I'),
    (53, 'Sonografia Torax', 40, 4, NULL, 'O', 'A'),
    (5001, 'Consulta Clínica Médica', 10101012, 1, 'CONSULTA-TESTE', 'O', 'A'),
    (5002, 'Ultrassonografia de Teste', 40901114, 1, 'EXAME-TESTE', 'O', 'A'),
    (9001, 'Ressonância Magnética de Ombro (TESTE)', 900001, 4, 'EXAME-SINTETICO-RM', 'O', 'A'),
    (9002, 'Ultrassonografia de Abdome Total (TESTE)', 900002, 4, 'EXAME-SINTETICO-US', 'O', 'A'),
    (9003, 'Colonoscopia (TESTE)', 900003, 4, 'EXAME-SINTETICO-COLO', 'O', 'A')
ON CONFLICT (nr_sequencia) DO UPDATE SET
    ds_proc_exame = EXCLUDED.ds_proc_exame,
    cd_procedimento = EXCLUDED.cd_procedimento,
    ie_origem_proced = EXCLUDED.ie_origem_proced,
    cd_integracao = EXCLUDED.cd_integracao,
    ie_tipo = EXCLUDED.ie_tipo,
    ie_situacao = EXCLUDED.ie_situacao;

INSERT INTO proc_interno_orientacao (
    nr_sequencia, cd_estabelecimento, nr_seq_proc_interno,
    ie_tipo_orientacao, ds_orientacao, nm_usuario, dt_atualizacao
) VALUES
    (
        1, 1, 20, 'R',
        '<html tasy="html5"><body><p style="text-align:left; font-size:16px;"><span style="font-size:16px;">Prueba</span></p></body></html>',
        'ORACLE', CURRENT_TIMESTAMP
    ),
    (
        3, 1, 53, 'P',
        '<html tasy="html5"><body>Texto Estandar Prueba</body></html>',
        'ORACLE', CURRENT_TIMESTAMP
    ),
    (
        9001, 1, 9001, 'P',
        '<html tasy="html5"><body>'
        || '<h3>1. CHEGADA</h3><ul><li>Chegar com 45 minutos de antecedência ao horário agendado.</li></ul>'
        || '<h3>2. JEJUM</h3><ul><li>Realizar jejum de 3 horas antes do exame.</li></ul>'
        || '<h3>3. ALERGIA</h3><p>Informe sobre qualquer alergia, incluindo:</p><ul><li>Frutos do mar e camarão, medicamentos.</li><li>Rinite, bronquite, sinusite, asma.</li><li>Reação alérgica a contraste em exames anteriores.</li></ul>'
        || '<p>SE HOUVER ALERGIA, SERÁ FORNECIDA UMA RECEITA PARA O PREPARO MEDICAMENTOSO COM ANTIALÉRGICO PARA SER REALIZADO ANTES DO EXAME.</p>'
        || '<h3>4. INSUFICIÊNCIA RENAL CRÔNICA</h3><ul><li>Para pacientes que realizam hemodiálise: deve-se agendar a hemodiálise após a realização do exame, no mesmo dia.</li></ul>'
        || '<h3>5. IMPLANTE OU PRÓTESE</h3><ul><li>Informar sobre a presença de marcapasso, stent cardíaco, implante coclear, aparelho auditivo, clips de aneurisma ou próteses metálicas.</li></ul>'
        || '<h3>6. INSTRUÇÃO IMPORTANTE - Referente à preparação do exame</h3><ul><li>Remover acessórios metálicos da roupa e do corpo (piercing).</li><li>Realizar o exame 7 dias após a realização de tatuagens.</li><li>Vir com cabelos secos e não usar maquiagem.</li></ul>'
        || '<h3>7. TRAQUEOSTOMIA</h3><ul><li>Se possuir cânula de traqueostomia metálica, essa deve ser substituída por outro material (plástico ou silicone); o hospital não realiza troca.</li></ul>'
        || '<h3>8. DOCUMENTAÇÃO NECESSÁRIA</h3><ul><li>Trazer exames laboratoriais recentes, incluindo creatinina (função renal).</li><li>Trazer EXAMES ANTERIORES relacionados à parte do corpo a ser estudada, caso não tenham sido realizados no Hospital Dona Helena.</li></ul>'
        || '</body></html>',
        'SINTETICO', CURRENT_TIMESTAMP
    ),
    (
        9002, 1, 9002, 'P',
        '<html tasy="html5"><body><h2>ORIENTAÇÃO SINTÉTICA - NÃO UTILIZAR EM PRODUÇÃO</h2><h3>Ultrassonografia de Abdome Total</h3><ol><li><strong>Chegada:</strong> comparecer com antecedência.</li><li><strong>Preparo:</strong> confirmar com a unidade o preparo correspondente à idade e à condição clínica.</li><li><strong>Medicamentos:</strong> não suspender medicamentos sem orientação profissional.</li><li><strong>Documentos:</strong> apresentar documento, cartão do convênio e solicitação médica.</li></ol></body></html>',
        'SINTETICO', CURRENT_TIMESTAMP
    ),
    (
        9003, 1, 9003, 'P',
        '<html tasy="html5"><body><h2>ORIENTAÇÃO SINTÉTICA - NÃO UTILIZAR EM PRODUÇÃO</h2><h3>Colonoscopia</h3><ol><li><strong>Preparo:</strong> confirmar e seguir exclusivamente o preparo fornecido pela equipe assistencial.</li><li><strong>Acompanhante:</strong> verificar previamente a necessidade de acompanhante adulto.</li><li><strong>Medicamentos:</strong> informar todos os medicamentos em uso e não suspender nenhum sem orientação.</li><li><strong>Documentos:</strong> apresentar documento, cartão do convênio e solicitação médica.</li></ol></body></html>',
        'SINTETICO', CURRENT_TIMESTAMP
    )
ON CONFLICT (nr_sequencia) DO UPDATE SET
    cd_estabelecimento = EXCLUDED.cd_estabelecimento,
    nr_seq_proc_interno = EXCLUDED.nr_seq_proc_interno,
    ie_tipo_orientacao = EXCLUDED.ie_tipo_orientacao,
    ds_orientacao = EXCLUDED.ds_orientacao,
    nm_usuario = EXCLUDED.nm_usuario,
    dt_atualizacao = EXCLUDED.dt_atualizacao;

-- Horários são relativos à data de inicialização para não envelhecerem.
INSERT INTO agenda_consulta (
    nr_sequencia, cd_agenda, dt_agenda, ie_status_agenda,
    ie_classif_agenda, cd_especialidade
) VALUES
    (6001, 3001, CURRENT_DATE + INTERVAL '1 day 09 hours', 'L', 'N', 10),
    (6002, 3001, CURRENT_DATE + INTERVAL '1 day 14 hours', 'L', 'N', 10),
    (6003, 3002, CURRENT_DATE + INTERVAL '2 days 10 hours', 'L', 'N', 20),
    (6004, 3002, CURRENT_DATE + INTERVAL '3 days 15 hours', 'L', 'N', 20),
    (6101, 3001, CURRENT_DATE + INTERVAL '4 days 11 hours', 'N', 'N', 10)
ON CONFLICT (nr_sequencia) DO UPDATE SET
    cd_agenda = EXCLUDED.cd_agenda,
    dt_agenda = EXCLUDED.dt_agenda,
    ie_status_agenda = EXCLUDED.ie_status_agenda,
    cd_especialidade = EXCLUDED.cd_especialidade,
    cd_pessoa_fisica = NULL,
    nm_paciente = NULL,
    dt_nascimento_pac = NULL,
    cd_convenio = NULL,
    dt_agendamento = NULL,
    dt_confirmacao = NULL,
    dt_cancelamento = NULL;

UPDATE agenda_consulta
SET cd_pessoa_fisica = '1001',
    nm_paciente = 'Paciente Teste',
    cd_convenio = 15,
    dt_nascimento_pac = TIMESTAMP '1990-05-20 00:00:00',
    dt_agendamento = CURRENT_TIMESTAMP
WHERE nr_sequencia = 6101;

-- Como no Tasy, horário livre de exame não traz procedimento; os exames da
-- agenda são inferidos das marcações anteriores (7101, 7102 e 7103).
INSERT INTO agenda_paciente (
    nr_sequencia, cd_agenda, dt_agenda, hr_inicio, ie_status_agenda,
    cd_procedimento, ie_origem_proced, nr_seq_proc_interno
) VALUES
    (7001, 4001, CURRENT_DATE + INTERVAL '1 day',
     CURRENT_DATE + INTERVAL '1 day 08 hours', 'L', NULL, NULL, NULL),
    (7002, 4001, CURRENT_DATE + INTERVAL '2 days',
     CURRENT_DATE + INTERVAL '2 days 13 hours', 'L', NULL, NULL, NULL),
    (7101, 4001, CURRENT_DATE - INTERVAL '30 days',
     CURRENT_DATE - INTERVAL '30 days' + INTERVAL '09 hours', 'E', 40901114, 1, 5002),
    (7102, 4001, CURRENT_DATE - INTERVAL '20 days',
     CURRENT_DATE - INTERVAL '20 days' + INTERVAL '10 hours', 'E', 900002, 4, 9002),
    (7103, 4001, CURRENT_DATE - INTERVAL '10 days',
     CURRENT_DATE - INTERVAL '10 days' + INTERVAL '14 hours', 'E', 900001, 4, 9001)
ON CONFLICT (nr_sequencia) DO UPDATE SET
    cd_agenda = EXCLUDED.cd_agenda,
    dt_agenda = EXCLUDED.dt_agenda,
    hr_inicio = EXCLUDED.hr_inicio,
    ie_status_agenda = EXCLUDED.ie_status_agenda,
    cd_procedimento = EXCLUDED.cd_procedimento,
    ie_origem_proced = EXCLUDED.ie_origem_proced,
    nr_seq_proc_interno = EXCLUDED.nr_seq_proc_interno,
    cd_pessoa_fisica = NULL,
    nm_paciente = NULL,
    dt_nascimento_pac = NULL,
    cd_convenio = NULL;

SELECT setval('pessoa_fisica_seq', 10000, false);
SELECT setval('agenda_consulta_seq', 10000, false);
SELECT setval('agenda_consulta_proc_seq', 10000, false);
SELECT setval('agenda_paciente_seq', 10000, false);
