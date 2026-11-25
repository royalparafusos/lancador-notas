package br.com.lancadornotas.ocr;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

/** Reduz e converte a foto do celular para JPEG de tamanho aceitável pela API. */
final class ImagemUtil {

    private static final int LADO_MAXIMO = 2400;

    private ImagemUtil() {}

    static byte[] prepararParaEnvio(Path foto, long limiteBytes) throws IOException {
        BufferedImage original = ImageIO.read(foto.toFile());
        if (original == null) {
            throw new IOException("Formato de imagem não suportado. Use JPG ou PNG.");
        }

        int lado = LADO_MAXIMO;
        float qualidade = 0.88f;
        while (true) {
            BufferedImage img = redimensionar(original, lado);
            byte[] jpg = paraJpeg(img, qualidade);
            if (jpg.length <= limiteBytes || lado < 800) return jpg;
            lado = (int) (lado * 0.8);
            qualidade = Math.max(0.6f, qualidade - 0.08f);
        }
    }

    private static BufferedImage redimensionar(BufferedImage src, int ladoMax) {
        int w = src.getWidth(), h = src.getHeight();
        double escala = Math.min(1.0, (double) ladoMax / Math.max(w, h));
        int nw = (int) Math.round(w * escala), nh = (int) Math.round(h * escala);
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return dst;
    }

    private static byte[] paraJpeg(BufferedImage img, float qualidade) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(qualidade);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }
}
