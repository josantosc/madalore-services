package br.com.mandalore.agendamentoconversa.adapter.in.web.controller;

import br.com.mandalore.agendamentoconversa.application.domain.model.Atendimento;
import io.nayuki.qrcodegen.QrCode;

/** QR code em SVG (um path só, módulos pretos sobre fundo branco, com a margem de 4 módulos da norma). */
public final class QrSvg {

    private static final int MARGEM = 4;

    private QrSvg() {
    }

    /** O que a TV e a recepção recebem: o código para digitar e o QR do link do WhatsApp. */
    public record Checkin(String dia, String codigo, String svg) {
        static Checkin de(Atendimento.CheckinDoDia doDia) {
            return new Checkin(doDia.dia().toString(), doDia.codigo(), QrSvg.de(doDia.link()));
        }
    }

    static String de(String texto) {
        QrCode qr = QrCode.encodeText(texto, QrCode.Ecc.MEDIUM);
        int lado = qr.size + MARGEM * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    path.append('M').append(x + MARGEM).append(',').append(y + MARGEM).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + lado + " " + lado
                + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code do check-in\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"" + path + "\" fill=\"#000\"/></svg>";
    }
}
