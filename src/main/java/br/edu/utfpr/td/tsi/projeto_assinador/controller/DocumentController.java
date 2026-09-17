package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.DocumentResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.SignerDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.service.DocumentService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.SignatureRequestService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final SignatureRequestService signatureRequestService;

    @GetMapping
    public String listDocuments(@RequestParam(value = "filter", required = false, defaultValue = "all") String filter,
                                 HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");

        if (userId == null) {
            redirectAttributes.addFlashAttribute("error", "Você precisa estar logado.");
            return "redirect:/login";
        }

        List<DocumentResponseDTO> documents = documentService.getDocumentsForUser(userId, userEmail, filter);
        long pendingCount = documents.stream().filter(d -> d.getUserPendingToken() != null).count();

        model.addAttribute("documents", documents);
        model.addAttribute("filter", filter);
        model.addAttribute("pendingCount", pendingCount);
        return "documents/list";
    }

    @GetMapping("/new")
    public String newDocumentForm(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        if (session.getAttribute("userId") == null) {
            redirectAttributes.addFlashAttribute("error", "Você precisa estar logado.");
            return "redirect:/login";
        }
        model.addAttribute("userName", session.getAttribute("userName"));
        model.addAttribute("userEmail", session.getAttribute("userEmail"));
        return "documents/new";
    }

    @PostMapping
    public String createDocument(
            @RequestParam("arquivo") MultipartFile file,
            @RequestParam("signerEmails") List<String> signerEmails,
            @RequestParam("signerNames") List<String> signerNames,
            @RequestParam(value = "includeSelf", required = false) String includeSelf,
            @RequestParam(value = "expirationHours", defaultValue = "720") int expirationHours,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");
        String userName = (String) session.getAttribute("userName");

        if (userId == null) {
            redirectAttributes.addFlashAttribute("error", "Você precisa estar logado.");
            return "redirect:/login";
        }

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Selecione um arquivo PDF.");
            return "redirect:/documents/new";
        }

        // Clamp expiration to max 30 days (720 hours)
        if (expirationHours < 1) expirationHours = 1;
        if (expirationHours > 720) expirationHours = 720;

        List<SignerDTO> signers = new ArrayList<>();

        // Add owner as signer if requested
        if ("on".equals(includeSelf)) {
            signers.add(SignerDTO.builder().email(userEmail).name(userName).step(0).build());
        }

        for (int i = 0; i < signerEmails.size(); i++) {
            String email = signerEmails.get(i).trim();
            String name = (i < signerNames.size()) ? signerNames.get(i).trim() : email;
            if (!email.isEmpty()) {
                // Avoid duplicate if owner already added
                if (email.equalsIgnoreCase(userEmail) && "on".equals(includeSelf)) {
                    continue;
                }
                signers.add(SignerDTO.builder().email(email).name(name).step(0).build());
            }
        }

        if (signers.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Adicione pelo menos um assinante.");
            return "redirect:/documents/new";
        }

        try {
            DocumentResponseDTO doc = documentService.createDocument(
                    userId, userEmail, userName, file, signers, expirationHours);
            redirectAttributes.addFlashAttribute("success",
                    "Documento criado e convites enviados com sucesso!");
            return "redirect:/documents/" + doc.getId();
        } catch (Exception e) {
            log.error("Erro ao criar documento: {}", e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Erro ao criar documento: " + e.getMessage());
            return "redirect:/documents/new";
        }
    }

    @GetMapping("/{id}")
    public String documentDetail(@PathVariable String id, HttpSession session, Model model,
                                  RedirectAttributes redirectAttributes) {
        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");

        if (userId == null) {
            redirectAttributes.addFlashAttribute("error", "Você precisa estar logado.");
            return "redirect:/login";
        }

        try {
            DocumentResponseDTO document = documentService.getDocument(id, userId, userEmail);
            model.addAttribute("document", document);
            model.addAttribute("isOwner", true);
            model.addAttribute("userEmail", userEmail);
            return "documents/detail";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/documents";
        }
    }

    @PostMapping("/{id}/cancel")
    public String cancelDocument(@PathVariable String id, HttpSession session,
                                  RedirectAttributes redirectAttributes) {
        String userId = (String) session.getAttribute("userId");

        try {
            documentService.cancelDocument(id, userId);
            redirectAttributes.addFlashAttribute("success", "Documento cancelado com sucesso.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/documents";
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<ByteArrayResource> downloadPdf(@PathVariable String id, HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");

        byte[] pdf = documentService.downloadCurrentPdf(id, userId, userEmail);
        ByteArrayResource resource = new ByteArrayResource(pdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"documento-assinado.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(resource);
    }

    @GetMapping("/{id}/view")
    public ResponseEntity<ByteArrayResource> viewPdf(@PathVariable String id, HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");

        byte[] pdf = documentService.downloadCurrentPdf(id, userId, userEmail);
        ByteArrayResource resource = new ByteArrayResource(pdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"documento.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(resource);
    }

    @GetMapping("/{id}/download-original")
    public ResponseEntity<ByteArrayResource> downloadOriginalPdf(@PathVariable String id, HttpSession session) {
        String userId = (String) session.getAttribute("userId");
        String userEmail = (String) session.getAttribute("userEmail");

        byte[] pdf = documentService.downloadOriginalPdf(id, userId, userEmail);
        ByteArrayResource resource = new ByteArrayResource(pdf);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"documento-original.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdf.length)
                .body(resource);
    }

    @PostMapping("/{id}/remind/{requestId}")
    public String sendReminder(@PathVariable String id, @PathVariable String requestId,
                                HttpSession session, RedirectAttributes redirectAttributes) {
        String userId = (String) session.getAttribute("userId");

        try {
            signatureRequestService.sendReminder(requestId, userId);
            redirectAttributes.addFlashAttribute("success", "Lembrete enviado com sucesso.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/documents/" + id;
    }
}
