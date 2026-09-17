package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerIdentityDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.model.SignatureRequest;
import br.edu.utfpr.td.tsi.projeto_assinador.model.enums.SignatureRequestStatus;
import br.edu.utfpr.td.tsi.projeto_assinador.service.SignatureRequestService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.SignatureService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.UserService;
import br.edu.utfpr.td.tsi.projeto_assinador.util.CpfValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Slf4j
@Controller
@RequiredArgsConstructor
public class SigningController {

    private final SignatureRequestService signatureRequestService;
    private final UserService userService;

    @Value("${app.base-url:}")
    private String appBaseURL;

    @GetMapping("/sign")
    public String signLanding(@RequestParam("token") String token, HttpSession session,
                               Model model, RedirectAttributes redirectAttributes) {
        try {
            SignatureRequest request = signatureRequestService.resolveToken(token);

            if (request.getStatus() != SignatureRequestStatus.PENDING) {
                model.addAttribute("error", "Esta solicitação de assinatura não está mais disponível.");
                return "documents/sign-error";
            }

            // Check if user is logged in
            String userEmail = (String) session.getAttribute("userEmail");
            if (userEmail == null) {
                String signerEmail = request.getSignerEmail();
                session.setAttribute("redirectAfterLogin", "/sign?token=" + token);

                if (!userService.isEmailVerified(signerEmail)) {
                    java.util.Optional<String> verificationToken =
                            userService.getOrCreateSignerVerificationToken(signerEmail);
                    if (verificationToken.isPresent()) {
                        redirectAttributes.addAttribute("token", verificationToken.get());
                        return "redirect:/verify-email";
                    }
                }

                redirectAttributes.addAttribute("email", signerEmail);
                return "redirect:/login";
            }

            // Verify email matches
            if (!userEmail.equalsIgnoreCase(request.getSignerEmail())) {
                model.addAttribute("error",
                        "Este link de assinatura foi enviado para " + request.getSignerEmail() +
                        ". Faça login com o email correto.");
                return "documents/sign-error";
            }

            // Redirect to preview
            return "redirect:/sign/preview?token=" + token;

        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            return "documents/sign-error";
        }
    }

    @GetMapping("/sign/preview")
    public String signPreview(@RequestParam("token") String token, HttpSession session, Model model) {
        String userEmail = (String) session.getAttribute("userEmail");
        if (userEmail == null) {
            return "redirect:/login";
        }

        try {
            SignatureRequest request = signatureRequestService.resolveTokenForSigner(token, userEmail);

            model.addAttribute("token", token);
            model.addAttribute("requestId", request.getId());
            model.addAttribute("userName", SignatureService.shortenName((String) session.getAttribute("userName")));
            model.addAttribute("userEmail", userEmail);
            model.addAttribute("cpfMasked", CpfValidator.mask((String) session.getAttribute("userCpf")));
            model.addAttribute("signDateTime", java.time.format.DateTimeFormatter
                    .ofPattern("dd/MM/yyyy HH:mm:ss").format(java.time.LocalDateTime.now()));
            model.addAttribute("documentHash", "");
            model.addAttribute("validatorURL", appBaseURL + "/validator");
            return "documents/sign";

        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            return "documents/sign-error";
        }
    }

    @GetMapping("/sign/preview/pdf")
    public ResponseEntity<ByteArrayResource> signPreviewPdf(@RequestParam("token") String token,
                                                              HttpSession session) {
        if (session.getAttribute("userEmail") == null) {
            return ResponseEntity.status(401).build();
        }

        try {
            String userEmail = (String) session.getAttribute("userEmail");
            SignatureRequest request = signatureRequestService.resolveTokenForSigner(token, userEmail);
            byte[] pdf = signatureRequestService.getCurrentPdfForRequest(request.getId(), userEmail);
            ByteArrayResource resource = new ByteArrayResource(pdf);
            return ResponseEntity.ok()
                    .contentLength(pdf.length)
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/sign/execute")
    public String executeSign(
            @RequestParam("token") String token,
            @RequestParam("stampX") double stampX,
            @RequestParam("stampYFromBottom") double stampYFromBottom,
            @RequestParam("pageNumber") int pageNumber,
            HttpSession session,
            HttpServletRequest servletRequest,
            RedirectAttributes redirectAttributes) {

        String userEmail = (String) session.getAttribute("userEmail");
        if (userEmail == null) {
            return "redirect:/login";
        }

        try {
            SignatureRequest request = signatureRequestService.resolveTokenForSigner(token, userEmail);

            String userName = (String) session.getAttribute("userName");
            String userCpf = (String) session.getAttribute("userCpf");

            if (userCpf == null || userCpf.isBlank()) {
                redirectAttributes.addFlashAttribute("error",
                        "É necessário cadastrar seu CPF no perfil antes de assinar.");
                return "redirect:/account";
            }

            SignerIdentityDTO signerIdentity = SignerIdentityDTO.builder()
                    .fullName(userName)
                    .email(userEmail)
                    .cpf(userCpf)
                    .build();

            int normalizedPage = Math.max(pageNumber, 1);
            SignatureService.SignaturePlacement placement = new SignatureService.SignaturePlacement(
                    normalizedPage - 1, stampX, stampYFromBottom);

            signatureRequestService.signDocument(
                    request.getId(), signerIdentity, placement,
                    (String) session.getAttribute("userId"), servletRequest.getRemoteAddr());

            redirectAttributes.addFlashAttribute("success", "Documento assinado com sucesso!");
            return "redirect:/documents/" + request.getDocumentId();

        } catch (Exception e) {
            log.error("Erro ao assinar: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Erro ao assinar: " + e.getMessage());
            return "redirect:/sign/preview?token=" + token;
        }
    }

    @PostMapping("/sign/reject")
    public String rejectSign(
            @RequestParam("token") String token,
            @RequestParam(value = "reason", required = false) String reason,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userEmail = (String) session.getAttribute("userEmail");
        if (userEmail == null) {
            return "redirect:/login";
        }

        try {
            SignatureRequest request = signatureRequestService.resolveTokenForSigner(token, userEmail);
            signatureRequestService.rejectSignature(request.getId(), userEmail, reason);
            redirectAttributes.addFlashAttribute("success", "Assinatura recusada.");
            return "redirect:/documents";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/documents";
        }
    }
}
