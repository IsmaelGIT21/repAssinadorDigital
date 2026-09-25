package br.edu.utfpr.td.tsi.projeto_assinador.controller;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PdfController {
    @GetMapping("/pdf")
    public String pdf() {
        return "pdf-upload";
    }
}
