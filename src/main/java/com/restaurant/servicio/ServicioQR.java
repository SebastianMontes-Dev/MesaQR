package com.restaurant.servicio;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.restaurant.excepcion.QrException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * Servicio para generación de códigos QR.
 * Genera imágenes PNG con la URL de acceso al menú de cada mesa.
 */
@Service
public class ServicioQR {

    @Value("${restaurant.qr.tamanio:300}")
    private int tamanioQr;

    /**
     * Genera un código QR en formato PNG para una mesa específica.
     *
     * @param mesaId  identificador de la mesa
     * @param baseUrl URL base del servidor
     * @param token   token de sesión de la mesa
     * @return bytes de la imagen PNG del código QR
     */
    public byte[] generarQR(Long mesaId, String baseUrl, String token) {
        try {
            String url = baseUrl + "/menu/" + mesaId + "#token=" + token;

            QRCodeWriter escritor = new QRCodeWriter();
            BitMatrix matriz = escritor.encode(url, BarcodeFormat.QR_CODE, tamanioQr, tamanioQr);

            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matriz, "PNG", salida);
            return salida.toByteArray();
        } catch (Exception e) {
            throw new QrException("Error al generar código QR: " + e.getMessage(), e);
        }
    }
}
