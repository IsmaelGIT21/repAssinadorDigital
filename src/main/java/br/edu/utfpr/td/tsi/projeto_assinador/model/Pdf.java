package br.edu.utfpr.td.tsi.projeto_assinador.model;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("pdfs")
public class Pdf {

    @Id
    private String id;
    private String nome;
    private String proprietario;
    private String arquivoOriginalId;
    private String arquivoAssinadoId;
    private String hash;
    private List<DimensaoPagina> paginas;
    private Instant criadoEm;
    private Instant assinadoEm;

    @Version
    private Long versao;

    public Pdf() {
        
    }

    public Pdf(String nome, String proprietario, String arquivoOriginalId, String hash, List<DimensaoPagina> paginas) {
        this.nome = nome;
        this.proprietario = proprietario;
        this.arquivoOriginalId = arquivoOriginalId;
        this.hash = hash;
        this.paginas = paginas;
        this.criadoEm = Instant.now();
    }

    public boolean isAssinado() {
        return arquivoAssinadoId != null;
    }

    public String getArquivoAtualId() {
        return isAssinado() ? arquivoAssinadoId : arquivoOriginalId;
    }

    public int getTotalPaginas() {
        return paginas.size();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public String getProprietario() {
        return proprietario;
    }

    public void setProprietario(String proprietario) {
        this.proprietario = proprietario;
    }

    public String getArquivoOriginalId() {
        return arquivoOriginalId;
    }

    public void setArquivoOriginalId(String arquivoOriginalId) {
        this.arquivoOriginalId = arquivoOriginalId;
    }

    public String getArquivoAssinadoId() {
        return arquivoAssinadoId;
    }

    public void setArquivoAssinadoId(String arquivoAssinadoId) {
        this.arquivoAssinadoId = arquivoAssinadoId;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public List<DimensaoPagina> getPaginas() {
        return paginas;
    }

    public void setPaginas(List<DimensaoPagina> paginas) {
        this.paginas = paginas;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void setCriadoEm(Instant criadoEm) {
        this.criadoEm = criadoEm;
    }

    public Instant getAssinadoEm() {
        return assinadoEm;
    }

    public void setAssinadoEm(Instant assinadoEm) {
        this.assinadoEm = assinadoEm;
    }

    public Long getVersao() {
        return versao;
    }

    public void setVersao(Long versao) {
        this.versao = versao;
    }
}
