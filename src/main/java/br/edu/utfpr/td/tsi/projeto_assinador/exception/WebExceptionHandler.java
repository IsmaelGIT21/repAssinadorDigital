package br.edu.utfpr.td.tsi.projeto_assinador.exception;

import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import lombok.extern.slf4j.Slf4j;

/**
 * Global exception handler for MVC controllers (Thymeleaf views).
 * Handles exceptions and returns appropriate view responses with error messages.
 */
@Slf4j
@ControllerAdvice(annotations = org.springframework.stereotype.Controller.class)
public class WebExceptionHandler {

    /**
     * Handle UserAlreadyExistsException.
     * Returns to the registration page with error message.
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public String handleUserAlreadyExists(UserAlreadyExistsException ex, Model model) {
        log.warn("UserAlreadyExistsException: {}", ex.getMessage());
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("registrationDTO", new br.edu.utfpr.td.tsi.projeto_assinador.dto.UserRegistrationDTO());
        return "auth/register";
    }

    /**
     * Handle UserNotFoundException.
     * Returns to login page with generic error message for security.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public String handleUserNotFound(UserNotFoundException ex, Model model) {
        log.warn("UserNotFoundException: {}", ex.getMessage());
        model.addAttribute("error", "Email ou senha inválidos");
        model.addAttribute("loginDTO", new br.edu.utfpr.td.tsi.projeto_assinador.dto.UserLoginDTO());
        return "auth/login";
    }

    /**
     * Handle InvalidCredentialsException.
     * Returns to login page with generic error message for security.
     */
    @ExceptionHandler(InvalidCredentialsException.class)
    public String handleInvalidCredentials(InvalidCredentialsException ex, Model model) {
        log.warn("InvalidCredentialsException: {}", ex.getMessage());
        model.addAttribute("error", "Email ou senha inválidos");
        model.addAttribute("loginDTO", new br.edu.utfpr.td.tsi.projeto_assinador.dto.UserLoginDTO());
        return "auth/login";
    }

    /**
     * Handle EmailNotVerifiedException.
     * Returns to login page with verification required message.
     */
    @ExceptionHandler(EmailNotVerifiedException.class)
    public String handleEmailNotVerified(EmailNotVerifiedException ex, Model model) {
        log.warn("EmailNotVerifiedException: {}", ex.getMessage());
        model.addAttribute("error", ex.getMessage());
        model.addAttribute("loginDTO", new br.edu.utfpr.td.tsi.projeto_assinador.dto.UserLoginDTO());
        return "auth/login";
    }

    /**
     * Handle InvalidTokenException.
     * Returns to error page with token error message.
     */
    @ExceptionHandler(InvalidTokenException.class)
    public String handleInvalidToken(InvalidTokenException ex, RedirectAttributes redirectAttributes) {
        log.warn("InvalidTokenException: {}", ex.getMessage());
        redirectAttributes.addFlashAttribute("error", ex.getMessage());
        return "redirect:/login";
    }

    /**
     * Handle Invalid2FACodeException.
     * Returns to 2FA validation page with error message.
     */
    @ExceptionHandler(Invalid2FACodeException.class)
    public String handleInvalid2FACode(Invalid2FACodeException ex, Model model) {
        log.warn("Invalid2FACodeException: {}", ex.getMessage());
        model.addAttribute("error", ex.getMessage());
        return "auth/validate-2fa";
    }

    /**
     * Handle generic exceptions.
     * Returns to error page or redirects with error message.
     */
    @ExceptionHandler(Exception.class)
    public String handleGenericException(Exception ex, Model model, RedirectAttributes redirectAttributes) {
        log.error("Erro inesperado: ", ex);
        redirectAttributes.addFlashAttribute("error", "Ocorreu um erro inesperado. Tente novamente.");
        return "redirect:/";
    }
}
