package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.ValidationResultDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.service.ValidatorService;

@Controller
public class ValidatorController {

    @Autowired
    private ValidatorService validatorService;

    @GetMapping("/validator")
    public String showValidatorPage() {
        return "validator";
    }

    @PostMapping("/validate-document")
    public String validateDocument(@RequestParam("arquivo") MultipartFile file, Model model, RedirectAttributes redirectAttributes) {
        try {
            ValidationResultDTO result = validatorService.validateDocument(file.getBytes());
            
            redirectAttributes.addFlashAttribute("result", result);
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Erro ao processar o arquivo: " + e.getMessage());
        }
       return "redirect:/validator";
    }
}