package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class LoginController {

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, @RequestParam(required = false) String logout, CsrfToken csrf, Model model) {
        csrf.getToken();
        model.addAttribute("falhouLogin", error != null);
        model.addAttribute("saiu", logout != null);
        return "login";
    }
}
