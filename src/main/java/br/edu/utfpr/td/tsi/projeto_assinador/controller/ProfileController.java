package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.edu.utfpr.td.tsi.projeto_assinador.model.User;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.UserRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.util.CpfValidator;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequestMapping("/account")
@RequiredArgsConstructor
public class ProfileController {

    private final UserRepository userRepository;

    @GetMapping
    public String showProfile(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        String userId = (String) session.getAttribute("userId");

        if (userId == null) {
            redirectAttributes.addFlashAttribute("error", "Você precisa estar logado para acessar o perfil.");
            return "redirect:/login";
        }

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            redirectAttributes.addFlashAttribute("error", "Usuário não encontrado.");
            return "redirect:/login";
        }

        model.addAttribute("user", user);
        model.addAttribute("maskedCpf", CpfValidator.mask(user.getCpf()));
        model.addAttribute("twoFactorEnabled", user.isTwoFactorEnabled());

        log.info("Exibindo perfil para usuário: {}", user.getEmail());
        return "profile";
    }
}
