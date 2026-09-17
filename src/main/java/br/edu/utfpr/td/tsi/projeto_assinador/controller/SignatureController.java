package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.DocumentResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.SignatureRequestRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.service.DocumentService;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class SignatureController {

    private final DocumentService documentService;
    private final SignatureRequestRepository signatureRequestRepository;

    @GetMapping("/")
    public String index() {
        return "redirect:/sign-pdf";
    }

    @GetMapping("/sign-pdf")
    public String signPdfPage() {
        return "index";
    }

    @PostMapping("/sign-pdf")
    public String quickSignPdf(@RequestParam("arquivo") MultipartFile file,
                              HttpSession session,
                              RedirectAttributes redirectAttributes,
                              Model model) {
        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");
        String userName = (String) session.getAttribute("userName");

        if (userId == null) {
            redirectAttributes.addFlashAttribute("error", "Você precisa estar logado.");
            return "redirect:/login";
        }

        if (file.isEmpty()) {
            model.addAttribute("error", "Selecione um arquivo PDF para continuar.");
            return "index";
        }

        try {
            List<SignerDTO> signers = List.of(
                    SignerDTO.builder().email(userEmail).name(userName).step(0).build());

            DocumentResponseDTO doc = documentService.createDocument(
                    userId, userEmail, userName, file, signers, 720);

            SignatureRequest request = signatureRequestRepository
                    .findByDocumentIdOrderBySigningStepAsc(doc.getId())
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Solicitação de assinatura não encontrada"));

            return "redirect:/sign?token=" + request.getToken();

        } catch (Exception e) {
            model.addAttribute("error", "Não foi possível preparar o PDF: " + e.getMessage());
            return "index";
        }
    }
}
