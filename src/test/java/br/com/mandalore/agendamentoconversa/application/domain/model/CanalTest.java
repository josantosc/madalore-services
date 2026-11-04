package br.com.mandalore.agendamentoconversa.application.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CanalTest {

    @Test
    void reconheceOsCanaisDoChatwoot() {
        assertThat(Canal.doChatwoot("Channel::Whatsapp", null)).isEqualTo(Canal.WHATSAPP);
        assertThat(Canal.doChatwoot("Channel::Instagram", null)).isEqualTo(Canal.INSTAGRAM);
        assertThat(Canal.doChatwoot("Channel::FacebookPage", null)).isEqualTo(Canal.MESSENGER);
        assertThat(Canal.doChatwoot("Channel::WebWidget", null)).isEqualTo(Canal.SITE);
        assertThat(Canal.doChatwoot("Channel::Api", null)).isEqualTo(Canal.OUTRO);
        assertThat(Canal.doChatwoot(null, null)).isEqualTo(Canal.OUTRO);
    }

    @Test
    void instagramLigadoPelaPaginaDoFacebookContaComoInstagram() {
        assertThat(Canal.doChatwoot("Channel::FacebookPage", "instagram_direct_message")).isEqualTo(Canal.INSTAGRAM);
    }
}
