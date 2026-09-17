package br.edu.utfpr.td.tsi.projeto_assinador.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ValidationResultDTO {
    private boolean signedByUs;

    @Builder.Default
    private List<SignatureInfoDTO> signatures = new ArrayList<>();

    public ValidationResultDTO(boolean signedByUs) {
        this.signedByUs = signedByUs;
        this.signatures = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignatureInfoDTO {
        private String signerName;
        private String signerCpf;
        private String signerEmail;
        private String signedAt;
        private String signatureReason;
        private String certificateIssuer;
        private String certificateSubjectDN;
        private boolean valid;
    }
}
