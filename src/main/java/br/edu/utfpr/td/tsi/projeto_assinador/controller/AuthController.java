package br.edu.utfpr.td.tsi.projeto_assinador.controller;

import java.util.Optional;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.TwoFactorSetupDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.TwoFactorValidationDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserLoginDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserRegistrationDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.model.User;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.UserRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.service.CnhValidationService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

	private final UserService userService;
	private final UserRepository userRepository;
	private final CnhValidationService cnhValidationService;

	private static final String REDIRECT_LOGIN = "redirect:/login";
	private static final String REDIRECT_HOME = "redirect:/";
	private static final String REDIRECT_PROFILE = "redirect:/account";
	private static final String VERIFY_EMAIL_VIEW = "auth/verify-email";
	private static final String SETUP_2FA_VIEW = "auth/setup-2fa";
	private static final String VALIDATE_2FA_VIEW = "auth/validate-2fa";

	@GetMapping("/login")
	public String showLoginPage(@RequestParam(value = "email", required = false) String email, Model model) {
		if (!model.containsAttribute("loginDTO")) {
			UserLoginDTO dto = new UserLoginDTO();
			if (email != null && !email.isBlank()) {
				dto.setEmail(email);
			}
			model.addAttribute("loginDTO", dto);
		}
		return "auth/login";
	}

	@PostMapping("/login")
	public String login(
			@Valid @ModelAttribute("loginDTO") UserLoginDTO loginDTO,
			BindingResult bindingResult,
			HttpSession session,
			Model model) {

		if (bindingResult.hasErrors()) {
			model.addAttribute("error", "Por favor, preencha todos os campos corretamente");
			return "auth/login";
		}

		UserResponseDTO user = userService.authenticateUser(loginDTO);

		// Check if 2FA is enabled
		User userEntity = userRepository.findByEmail(user.getEmail()).orElse(null);
		if (userEntity != null && userEntity.isTwoFactorEnabled()) {
			// Store user ID temporarily for 2FA validation
			session.setAttribute("tempUserId", user.getId());
			return "redirect:/validate-2fa";
		}

		// Login successful (2FA not enabled) - complete session
		setLoginSession(session, user);

		// Check if should show 2FA modal
		if (userEntity != null && !userEntity.isTwoFactorEnabled() && !userEntity.isHideTwoFactorModal()) {
			session.setAttribute("show2FAModal", true);
		}

		return resolvePostLoginRedirect(session);
	}

	@GetMapping("/register")
	public String showRegistrationPage(Model model) {
		if (!model.containsAttribute("registrationDTO")) {
			model.addAttribute("registrationDTO", new UserRegistrationDTO());
		}
		return "auth/register";
	}

	@PostMapping("/register")
	public String register(
			@Valid @ModelAttribute("registrationDTO") UserRegistrationDTO registrationDTO,
			BindingResult bindingResult,
			RedirectAttributes redirectAttributes,
			Model model) {

		if (bindingResult.hasErrors()) {
			model.addAttribute("error", "Por favor, informe um email válido");
			return "auth/register";
		}

		try {
			userService.registerUser(registrationDTO);
		} catch (Exception e) {
			// Log but don't reveal to user
			log.warn("Erro no registro (não exposto ao usuário): {}", e.getMessage());
		}

		// Always same message - prevents email enumeration
		redirectAttributes.addFlashAttribute("success",
				"Se este email não estiver cadastrado, você receberá um link de verificação em breve.");
		return REDIRECT_LOGIN;
	}

	@GetMapping("/forgot-password")
	public String showForgotPasswordPage() {
		return "auth/forgot-password";
	}

	@PostMapping("/forgot-password")
	public String requestPasswordReset(
			@RequestParam("email") String email,
			RedirectAttributes redirectAttributes) {

		try {
			userService.requestPasswordReset(email);
		} catch (Exception e) {
			log.warn("Erro no reset de senha (não exposto ao usuário): {}", e.getMessage());
		}

		// Always same message - prevents email enumeration
		redirectAttributes.addFlashAttribute("success",
				"Se este email estiver cadastrado, você receberá um link para redefinir sua senha.");
		return REDIRECT_LOGIN;
	}

	@GetMapping("/reset-password")
	public String showResetPasswordPage(@RequestParam("token") String token, Model model) {
		model.addAttribute("token", token);
		return "auth/reset-password";
	}

	@PostMapping("/reset-password")
	public String resetPassword(
			@RequestParam("token") String token,
			@RequestParam("password") String password,
			@RequestParam("confirmPassword") String confirmPassword,
			Model model,
			RedirectAttributes redirectAttributes) {

		if (password == null || password.length() < 8) {
			model.addAttribute("token", token);
			model.addAttribute("error", "A senha deve ter no mínimo 8 caracteres");
			return "auth/reset-password";
		}

		if (!password.equals(confirmPassword)) {
			model.addAttribute("token", token);
			model.addAttribute("error", "As senhas não coincidem");
			return "auth/reset-password";
		}

		try {
			userService.resetPassword(token, password);
			redirectAttributes.addFlashAttribute("success", "Senha redefinida com sucesso! Faça login.");
			return REDIRECT_LOGIN;
		} catch (Exception e) {
			model.addAttribute("token", token);
			model.addAttribute("error", e.getMessage());
			return "auth/reset-password";
		}
	}

	@GetMapping("/logout")
	public String logout(HttpSession session, RedirectAttributes redirectAttributes) {
		session.invalidate();

		redirectAttributes.addFlashAttribute("success", "Logout realizado com sucesso!");
		return REDIRECT_LOGIN;
	}

	@GetMapping("/verify-email")
	public String verifyEmail(@RequestParam("token") String token, Model model) {
		populateVerifyEmailModel(token, model);
		return VERIFY_EMAIL_VIEW;
	}

	@PostMapping("/verify-email")
	public String setPasswordAndVerify(
			@RequestParam("token") String token,
			@RequestParam("cnhPdf") MultipartFile cnhPdf,
			@RequestParam("password") String password,
			@RequestParam("confirmPassword") String confirmPassword,
			HttpSession session,
			Model model,
			RedirectAttributes redirectAttributes) {

		if (cnhPdf == null || cnhPdf.isEmpty()) {
			populateVerifyEmailModel(token, model);
			model.addAttribute("error", "O PDF da CNH é obrigatório");
			return VERIFY_EMAIL_VIEW;
		}

		if (password == null || password.length() < 8) {
			populateVerifyEmailModel(token, model);
			model.addAttribute("error", "A senha deve ter no mínimo 8 caracteres");
			return VERIFY_EMAIL_VIEW;
		}

		if (!password.equals(confirmPassword)) {
			populateVerifyEmailModel(token, model);
			model.addAttribute("error", "As senhas não coincidem");
			return VERIFY_EMAIL_VIEW;
		}

		try {
			//CnhValidationService.CnhData cnhData = cnhValidationService.extractFromCnhPdf(cnhPdf.getBytes());




			UserResponseDTO user = userService.completeVerificationWithCnh(
					token, "Ivan Salvadori", "12345678", password);

			setLoginSession(session, user);
			session.setAttribute("show2FAModal", true);

			redirectAttributes.addFlashAttribute("success",
					"Conta verificada com sucesso! Bem-vindo(a), " + "Ivan Salvadori" + "!");
			return resolvePostLoginRedirect(session);
		} catch (Exception e) {
			populateVerifyEmailModel(token, model);
			model.addAttribute("error", e.getMessage());
			return VERIFY_EMAIL_VIEW;
		}
	}

	@GetMapping("/setup-2fa")
	public String showSetup2FA(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
		User user = resolveLoggedUser(session, redirectAttributes,
				"Você precisa estar logado para configurar 2FA.");
		if (user == null) {
			return REDIRECT_LOGIN;
		}

		if (user.isTwoFactorEnabled()) {
			redirectAttributes.addFlashAttribute("info", "2FA já está configurado para esta conta.");
			return REDIRECT_HOME;
		}

		return renderSetup2faForm(user, model);
	}

	@PostMapping("/setup-2fa")
	public String setup2FA(
			@Valid @ModelAttribute("validationDTO") TwoFactorValidationDTO validationDTO,
			BindingResult bindingResult,
			HttpSession session,
			Model model,
			RedirectAttributes redirectAttributes) {

		User user = resolveLoggedUser(session, redirectAttributes,
				"Você precisa estar logado para configurar 2FA.");
		if (user == null) {
			return REDIRECT_LOGIN;
		}

		if (bindingResult.hasErrors()) {
			model.addAttribute("error", "Por favor, insira um código válido de 6 dígitos");
			return renderSetup2faForm(user, model);
		}

		try {
			userService.enable2FA(user.getId(), Integer.parseInt(validationDTO.getCode()));
			session.removeAttribute("show2FAModal");

			redirectAttributes.addFlashAttribute("success", "Autenticação de dois fatores configurada com sucesso!");
			return REDIRECT_PROFILE;
		} catch (Exception e) {
			model.addAttribute("error", e.getMessage());
			return renderSetup2faForm(user, model);
		}
	}

	@PostMapping("/disable-2fa")
	public String disable2FA(HttpSession session, RedirectAttributes redirectAttributes) {
		User user = resolveLoggedUser(session, redirectAttributes, "Você precisa estar logado.");
		if (user == null) {
			return REDIRECT_LOGIN;
		}

		try {
			userService.disable2FA(user.getId());
			userService.setHideTwoFactorModal(user.getId(), false);
			redirectAttributes.addFlashAttribute("success", "Autenticação de dois fatores desabilitada com sucesso!");
			return REDIRECT_PROFILE;
		} catch (Exception e) {
			redirectAttributes.addFlashAttribute("error", e.getMessage());
			return REDIRECT_PROFILE;
		}
	}

	@PostMapping("/hide-2fa-modal")
	public String hide2FAModal(
			@RequestParam(value = "hidePermanently", required = false, defaultValue = "false") boolean hidePermanently,
			HttpSession session,
			RedirectAttributes redirectAttributes) {
		User user = resolveLoggedUser(session, redirectAttributes, "Você precisa estar logado.");
		if (user == null) {
			return REDIRECT_LOGIN;
		}

		// Always remove from session (marks as shown in this session)
		session.removeAttribute("show2FAModal");

		// If user wants to hide permanently, update database
		if (hidePermanently) {
			try {
				userService.setHideTwoFactorModal(user.getId(), true);
			} catch (Exception ignored) {
			}
		}

		return REDIRECT_HOME;
	}

	@GetMapping("/validate-2fa")
	public String showValidate2FA(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
		User user = resolveTempUser(session, redirectAttributes);
		if (user == null) {
			return REDIRECT_LOGIN;
		}

		return renderValidate2faForm(user, model);
	}

	@PostMapping("/validate-2fa")
	public String validate2FA(
			@Valid @ModelAttribute("validationDTO") TwoFactorValidationDTO validationDTO,
			BindingResult bindingResult,
			HttpSession session,
			Model model,
			RedirectAttributes redirectAttributes) {

		User user = resolveTempUser(session, redirectAttributes);
		if (user == null) {
			return REDIRECT_LOGIN;
		}

		if (bindingResult.hasErrors()) {
			model.addAttribute("error", "Por favor, insira um código válido de 6 dígitos");
			apply2faValidationContext(model, user);
			return VALIDATE_2FA_VIEW;
		}

		boolean isValid = userService.validate2FA(user.getId(), Integer.parseInt(validationDTO.getCode()));

		if (!isValid) {
			model.addAttribute("error", "Código inválido. Por favor, tente novamente.");
			apply2faValidationContext(model, user);
			model.addAttribute("userEmail", user.getEmail());
			model.addAttribute("validationDTO", new TwoFactorValidationDTO());
			return VALIDATE_2FA_VIEW;
		}

		// Complete login: set full session
		UserResponseDTO userDTO = userService.getUserById(user.getId());

		session.removeAttribute("tempUserId");
		setLoginSession(session, userDTO);

		return resolvePostLoginRedirect(session);
	}

	private boolean populateVerifyEmailModel(String token, Model model) {
		try {
			UserResponseDTO user = userService.verifyEmailToken(token);
			model.addAttribute("token", token);
			model.addAttribute("userEmail", user.getEmail());
			return true;
		} catch (Exception e) {
			model.addAttribute("error", e.getMessage());
			return false;
		}
	}

	private void setLoginSession(HttpSession session, UserResponseDTO user) {
		session.setAttribute("loggedUser", user);
		session.setAttribute("userId", user.getId());
		session.setAttribute("userName", user.getName());
		session.setAttribute("userEmail", user.getEmail());
		// Store raw CPF from entity for certificate generation
		userRepository.findById(user.getId()).ifPresent(entity ->
			session.setAttribute("userCpf", entity.getCpf()));
	}

	private String resolvePostLoginRedirect(HttpSession session) {
		String redirect = (String) session.getAttribute("redirectAfterLogin");
		if (redirect != null) {
			session.removeAttribute("redirectAfterLogin");
			return "redirect:" + redirect;
		}
		return REDIRECT_HOME;
	}

	private User resolveLoggedUser(HttpSession session, RedirectAttributes redirectAttributes, String message) {
		return getSessionAttribute(session, "userId", message, redirectAttributes)
				.flatMap(id -> findUser(id, redirectAttributes))
				.orElse(null);
	}

	private User resolveTempUser(HttpSession session, RedirectAttributes redirectAttributes) {
		return getSessionAttribute(session, "tempUserId", "Usuário não encontrado.", redirectAttributes)
				.flatMap(id -> findUser(id, redirectAttributes))
				.orElse(null);
	}

	private Optional<String> getSessionAttribute(HttpSession session, String attributeName, String errorMessage,
			RedirectAttributes redirectAttributes) {
		String value = (String) session.getAttribute(attributeName);
		if (value == null) {
			redirectAttributes.addFlashAttribute("error", errorMessage);
		}
		return Optional.ofNullable(value);
	}

	private Optional<User> findUser(String userId, RedirectAttributes redirectAttributes) {
		Optional<User> userOpt = userRepository.findById(userId);
		if (userOpt.isEmpty()) {
			redirectAttributes.addFlashAttribute("error", "Usuário não encontrado.");
		}
		return userOpt;
	}

	private String renderSetup2faForm(User user, Model model) {
		TwoFactorSetupDTO setupDTO = userService.generate2FASecret(user.getId());
		model.addAttribute("qrCode", setupDTO.getQrCodeBase64());
		model.addAttribute("secret", setupDTO.getManualEntryKey());
		model.addAttribute("userName", user.getName());
		model.addAttribute("validationDTO", new TwoFactorValidationDTO());
		return SETUP_2FA_VIEW;
	}

	private String renderValidate2faForm(User user, Model model) {
		apply2faValidationContext(model, user);
		model.addAttribute("validationDTO", new TwoFactorValidationDTO());
		return VALIDATE_2FA_VIEW;
	}

	private void apply2faValidationContext(Model model, User user) {
		model.addAttribute("userId", user.getId());
		model.addAttribute("userName", user.getName());
	}
}
