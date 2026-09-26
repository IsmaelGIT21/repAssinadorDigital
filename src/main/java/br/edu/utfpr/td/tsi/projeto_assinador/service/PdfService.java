package br.edu.utfpr.td.tsi.projeto_assinador.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mongodb.client.gridfs.model.GridFSFile;

import br.edu.utfpr.td.tsi.projeto_assinador.exception.DocumentoNaoEncontradoException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.OperacaoInvalidaException;
import br.edu.utfpr.td.tsi.projeto_assinador.model.ConteudoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.DimensaoPagina;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Pdf;
import br.edu.utfpr.td.tsi.projeto_assinador.model.PosicaoEtiqueta;
import br.edu.utfpr.td.tsi.projeto_assinador.model.Signatario;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.PdfRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.util.ConversorCoordenadas;

@Service
public class PdfService {

    private static final float ESCALA_PREVIA = 1.5f;
    private static final int CARACTERES_HASH_ETIQUETA = 16;
    private static final int LIMITE_CABECALHO = 1024;
    private static final DateTimeFormatter FORMATO_DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final PdfRepository repositorio;
    private final GridFsTemplate arquivos;
    private final AssinaturaDigitalService assinaturaDigital;
    private final ZoneId fusoHorario;

    public PdfService(PdfRepository repositorio, GridFsTemplate arquivos, AssinaturaDigitalService assinaturaDigital, @Value("${spring.jackson.time-zone:America/Sao_Paulo}") String fusoHorario) {
        this.repositorio = repositorio;
        this.arquivos = arquivos;
        this.assinaturaDigital = assinaturaDigital;
        this.fusoHorario = ZoneId.of(fusoHorario);
    }

    public Pdf criar(MultipartFile arquivo, String proprietario) {

        if (arquivo == null || arquivo.isEmpty()) {
            throw new OperacaoInvalidaException("Selecione um arquivo PDF.");
        }

        byte[] conteudo = lerEnvio(arquivo);
        List<DimensaoPagina> paginas = lerPaginas(conteudo);
        String nome = nomeDoArquivo(arquivo.getOriginalFilename());
        String arquivoId = salvarArquivo(conteudo, nome);
        return repositorio.save(new Pdf(nome, proprietario, arquivoId, sha256(conteudo), paginas));
    }

    public Pdf buscar(String id, String proprietario) {
        return repositorio.findByIdAndProprietario(id, proprietario).orElseThrow(DocumentoNaoEncontradoException::new);
    }

    public byte[] renderizarPagina(String id, String proprietario, int numeroPagina) {
        Pdf pdf = buscar(id, proprietario);

        if (numeroPagina < 1 || numeroPagina > pdf.getTotalPaginas()) {
            throw new DocumentoNaoEncontradoException();
        }

        try (PDDocument documento = PDDocument.load(lerArquivo(pdf.getArquivoAtualId()))) {
            BufferedImage imagem = new PDFRenderer(documento).renderImage(numeroPagina - 1, ESCALA_PREVIA);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            ImageIO.write(imagem, "png", saida);
            return saida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao renderizar a página " + numeroPagina + " do PDF " + id, e);
        }
    }

    public Pdf assinar(String id, String proprietario, PosicaoEtiqueta posicao, Signatario signatario) {
        Pdf pdf = buscar(id, proprietario);

        if (pdf.isAssinado()) {
            throw new OperacaoInvalidaException("Este documento já foi assinado.");
        }

        if (posicao.pagina() > pdf.getTotalPaginas()) {
            throw new OperacaoInvalidaException("A página escolhida não existe neste documento.");
        }

        ZonedDateTime agora = ZonedDateTime.now(fusoHorario);
        ConteudoEtiqueta conteudo = new ConteudoEtiqueta(signatario, FORMATO_DATA_HORA.format(agora), pdf.getHash().substring(0, CARACTERES_HASH_ETIQUETA));

        byte[] assinado;

        try {
            assinado = assinaturaDigital.assinar(lerArquivo(pdf.getArquivoOriginalId()), posicao, conteudo,
                    GregorianCalendar.from(agora));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao assinar o PDF " + id, e);
        }

        String arquivoAssinadoId = salvarArquivo(assinado, pdf.getNome());
        pdf.setArquivoAssinadoId(arquivoAssinadoId);
        pdf.setAssinadoEm(agora.toInstant());
        try {
            return repositorio.save(pdf);
        } catch (OptimisticLockingFailureException e) {
            arquivos.delete(porId(arquivoAssinadoId));
            throw new OperacaoInvalidaException("Este documento já foi assinado.");
        }
    }

    public byte[] conteudoAtual(Pdf pdf) {
        return lerArquivo(pdf.getArquivoAtualId());
    }

    public ConteudoEtiqueta previaEtiqueta(Signatario signatario) {
        return new ConteudoEtiqueta(signatario, FORMATO_DATA_HORA.format(ZonedDateTime.now(fusoHorario)), "");
    }

    public String formatarDataHora(Pdf pdf) {
        return pdf.getAssinadoEm() == null ? "" : FORMATO_DATA_HORA.format(pdf.getAssinadoEm().atZone(fusoHorario));
    }

    private static List<DimensaoPagina> lerPaginas(byte[] conteudo) {
        byte[] inicio = Arrays.copyOf(conteudo, Math.min(conteudo.length, LIMITE_CABECALHO));

        if (!new String(inicio, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
            throw new OperacaoInvalidaException("O arquivo enviado não é um PDF válido.");
        }

        try (PDDocument documento = PDDocument.load(conteudo)) {
            if (documento.getNumberOfPages() == 0) {
                throw new OperacaoInvalidaException("O PDF enviado não possui páginas.");
            }

            List<DimensaoPagina> paginas = new ArrayList<>();

            for (PDPage pagina : documento.getPages()) {
                paginas.add(ConversorCoordenadas.dimensaoVisivel(pagina));
            }

            return paginas;

        } catch (InvalidPasswordException e) {
            throw new OperacaoInvalidaException("PDFs protegidos por senha não podem ser assinados.");
        } catch (IOException e) {
            throw new OperacaoInvalidaException("O arquivo enviado não é um PDF válido.");
        }
    }

    private static String nomeDoArquivo(String nomeOriginal) {
        String nome = nomeOriginal == null ? "" : nomeOriginal.strip();
        nome = nome.substring(Math.max(nome.lastIndexOf('/'), nome.lastIndexOf('\\')) + 1);

        if (nome.isBlank()) {
            return "documento.pdf";
        }
        return nome.length() > 200 ? nome.substring(nome.length() - 200) : nome;
    }

    private static byte[] lerEnvio(MultipartFile arquivo) {
        try {
            return arquivo.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado.", e);
        }
    }

    private String salvarArquivo(byte[] conteudo, String nome) {
        return arquivos.store(new ByteArrayInputStream(conteudo), nome, "application/pdf").toHexString();
    }

    private byte[] lerArquivo(String arquivoId) {
        GridFSFile arquivo = arquivos.findOne(porId(arquivoId));

        if (arquivo == null) {
            throw new IllegalStateException("Arquivo " + arquivoId + " não encontrado no GridFS.");
        }
        
        try (InputStream entrada = arquivos.getResource(arquivo).getInputStream()) {
            return entrada.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo " + arquivoId + " do GridFS.", e);
        }
    }

    private static Query porId(String arquivoId) {
        return Query.query(Criteria.where("_id").is(new ObjectId(arquivoId)));
    }

    private static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível na JVM.", e);
        }
    }
}
