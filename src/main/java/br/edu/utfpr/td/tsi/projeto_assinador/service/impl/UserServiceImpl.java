package br.edu.utfpr.td.tsi.projeto_assinador.service.impl;

import br.edu.utfpr.td.tsi.projeto_assinador.dto.ProfileUpdateDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.TwoFactorSetupDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserLoginDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserRegistrationDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.dto.UserResponseDTO;
import br.edu.utfpr.td.tsi.projeto_assinador.util.CpfValidator;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.EmailNotVerifiedException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.Invalid2FACodeException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.InvalidCredentialsException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.InvalidTokenException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.UserAlreadyExistsException;
import br.edu.utfpr.td.tsi.projeto_assinador.exception.UserNotFoundException;
import br.edu.utfpr.td.tsi.projeto_assinador.model.User;
import br.edu.utfpr.td.tsi.projeto_assinador.repository.UserRepository;
import br.edu.utfpr.td.tsi.projeto_assinador.service.EmailService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.TwoFactorAuthService;
import br.edu.utfpr.td.tsi.projeto_assinador.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Implementation of UserService interface.
 * Handles user management operations including registration, authentication,
 * and CRUD operations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TwoFactorAuthService twoFactorAuthService;

    @Override
    @Transactional
    public UserResponseDTO registerUser(UserRegistrationDTO registrationDTO) {
        log.info("Tentativa de registro com email: {}", registrationDTO.getEmail());

        Optional<User> existingUser = userRepository.findByEmail(registrationDTO.getEmail());

        // Already verified - send notice email with reset link instead of revealing that account exists
        if (existingUser.isPresent() && existingUser.get().isEmailVerified()) {
            log.info("Email já cadastrado e verificado, enviando aviso: {}", registrationDTO.getEmail());
            User user = existingUser.get();

            // Generate password reset token so the email includes a direct reset link
            String resetToken = RandomStringUtils.randomAlphanumeric(64);
            user.setPasswordResetToken(resetToken);
            user.setPasswordResetExpiry(LocalDateTime.now().plusHours(1));
            user.updateTimestamp();
            userRepository.save(user);

            emailService.sendAlreadyRegisteredNotice(user.getEmail(), user.getName(), resetToken);
            return mapToResponseDTO(user);
        }

        // Exists but not verified - delete and re-register
        if (existingUser.isPresent() && !existingUser.get().isEmailVerified()) {
            log.info("Removendo registro não verificado anterior para email: {}", registrationDTO.getEmail());
            userRepository.delete(existingUser.get());
        }

        // Generate email verification token
        String verificationToken = RandomStringUtils.randomAlphanumeric(64);
        LocalDateTime tokenExpiry = LocalDateTime.now().plusHours(24);

        User user = User.builder()
                .name(registrationDTO.getEmail())
                .email(registrationDTO.getEmail())
                .passwordHash(null)
                .emailVerified(false)
                .emailVerificationToken(verificationToken)
                .emailVerificationExpiry(tokenExpiry)
                .twoFactorEnabled(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        User savedUser = userRepository.save(user);
        log.info("Usuário registrado (verificação pendente): {}", savedUser.getId());

        emailService.sendVerificationEmail(savedUser.getEmail(), savedUser.getEmail(), verificationToken);

        return mapToResponseDTO(savedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponseDTO authenticateUser(UserLoginDTO loginDTO) {
        log.info("Tentando autenticar usuário: {}", loginDTO.getEmail());

        User user = userRepository.findByEmail(loginDTO.getEmail())
                .orElseThrow(() -> {
                    log.warn("Tentativa de login com email não encontrado ou inativo: {}", loginDTO.getEmail());
                    return new UserNotFoundException("Usuário não encontrado ou inativo");
                });

        if (!passwordEncoder.matches(loginDTO.getPassword(), user.getPasswordHash())) {
            log.warn("Tentativa de login com senha incorreta para o usuário: {}", loginDTO.getEmail());
            throw new InvalidCredentialsException("Credenciais inválidas");
        }

        // Check if email is verified
        if (!user.isEmailVerified()) {
            log.warn("Tentativa de login com email não verificado: {}", loginDTO.getEmail());
            throw new EmailNotVerifiedException(
                    "Por favor, verifique seu email antes de fazer login. " +
                            "Verifique sua caixa de entrada e spam.");
        }

        log.info("Usuário autenticado com sucesso: {}", user.getId());
        return mapToResponseDTO(user);
    }

    @Override
    @Transactional
    public UserResponseDTO verifyEmailToken(String token) {
        log.info("Verificando token de email");

        User user = getValidUserForVerification(token);

        log.info("Token válido para usuário: {}", user.getEmail());
        return mapToResponseDTO(user);
    }

    @Override
    @Transactional
    public UserResponseDTO setPasswordAndVerifyEmail(String token, String password) {
        log.info("Definindo senha e verificando email com token");

        User user = getValidUserForVerification(token);

        // Check if already verified
        if (user.isEmailVerified()) {
            log.info("Email já verificado para usuário: {}", user.getEmail());
            return mapToResponseDTO(user);
        }

        // Set password and verify email
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiry(null);
        user.updateTimestamp();

        User verifiedUser = userRepository.save(user);
        log.info("Senha definida e email verificado com sucesso para usuário: {}", verifiedUser.getEmail());

        return mapToResponseDTO(verifiedUser);
    }

    @Override
    @Transactional
    public UserResponseDTO completeVerificationWithCnh(String token, String name, String cpf, String password) {
        log.info("Completando verificação com dados da CNH");

        User user = getValidUserForVerification(token);

        if (user.isEmailVerified()) {
            log.info("Email já verificado para usuário: {}", user.getEmail());
            return mapToResponseDTO(user);
        }

        // Check CPF uniqueness
        String sanitizedCpf = CpfValidator.sanitize(cpf);
        Optional<User> existingCpfUser = userRepository.findByCpf(sanitizedCpf);
        if (existingCpfUser.isPresent() && !existingCpfUser.get().getId().equals(user.getId())) {
            if (existingCpfUser.get().isEmailVerified()) {
                throw new UserAlreadyExistsException("Já existe um usuário cadastrado com este CPF");
            } else {
                userRepository.delete(existingCpfUser.get());
            }
        }

        // Set identity data from CNH and password
        user.setName(name);
        user.setCpf(sanitizedCpf);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmailVerified(true);
        user.setEmailVerificationToken(null);
        user.setEmailVerificationExpiry(null);
        user.updateTimestamp();

        User verifiedUser = userRepository.save(user);
        log.info("Verificação completa com CNH para usuário: {} - Nome: {}", verifiedUser.getEmail(), name);

        return mapToResponseDTO(verifiedUser);
    }

    @Override
    @Transactional
    public TwoFactorSetupDTO generate2FASecret(String userId) {
        log.info("Gerando secret 2FA para usuário: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para gerar 2FA: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        // Generate secret
        String secret = twoFactorAuthService.generateSecretKey();

        // Save secret to user (but don't enable 2FA yet)
        user.setTwoFactorSecret(secret);
        user.updateTimestamp();
        userRepository.save(user);

        // Generate QR code
        String qrCodeBase64 = twoFactorAuthService.generateQRCodeImageBase64(user.getEmail(), secret);

        log.info("Secret 2FA gerado para usuário: {}", userId);

        return TwoFactorSetupDTO.builder()
                .secret(secret)
                .qrCodeBase64(qrCodeBase64)
                .manualEntryKey(secret)
                .build();
    }

    @Override
    @Transactional
    public UserResponseDTO enable2FA(String userId, int code) {
        log.info("Tentando habilitar 2FA para usuário: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para habilitar 2FA: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        if (user.getTwoFactorSecret() == null) {
            log.warn("Tentativa de habilitar 2FA sem secret gerado: {}", userId);
            throw new Invalid2FACodeException("Secret 2FA não foi gerado. Por favor, gere um novo QR Code.");
        }

        // Validate code
        if (!twoFactorAuthService.validateCode(user.getTwoFactorSecret(), code)) {
            log.warn("Código 2FA inválido para usuário: {}", userId);
            throw new Invalid2FACodeException("Código inválido. Por favor, tente novamente.");
        }

        // Enable 2FA
        user.setTwoFactorEnabled(true);
        user.updateTimestamp();
        User updatedUser = userRepository.save(user);

        log.info("2FA habilitado com sucesso para usuário: {}", userId);

        return mapToResponseDTO(updatedUser);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean validate2FA(String userId, int code) {
        log.info("Validando código 2FA para usuário: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para validar 2FA: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        if (!user.isTwoFactorEnabled() || user.getTwoFactorSecret() == null) {
            log.warn("2FA não está habilitado para usuário: {}", userId);
            return false;
        }

        boolean isValid = twoFactorAuthService.validateCode(user.getTwoFactorSecret(), code);
        log.info("Validação 2FA para usuário {}: {}", userId, isValid ? "sucesso" : "falhou");

        return isValid;
    }

    @Override
    @Transactional
    public UserResponseDTO updateUserName(String userId, String name) {
        log.info("Atualizando nome do usuário: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para atualizar nome: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        user.setName(name);
        user.updateTimestamp();
        User updatedUser = userRepository.save(user);

        log.info("Nome atualizado com sucesso para usuário: {}", userId);
        return mapToResponseDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserResponseDTO updateProfile(String userId, ProfileUpdateDTO profileUpdateDTO) {
        log.info("Atualizando perfil do usuário: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para atualizar perfil: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        user.updateTimestamp();
        User updatedUser = userRepository.save(user);

        log.info("Perfil atualizado com sucesso para usuário: {}", userId);
        return mapToResponseDTO(updatedUser);
    }

    @Override
    @Transactional
    public UserResponseDTO disable2FA(String userId) {
        log.info("Desabilitando 2FA para usuário: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para desabilitar 2FA: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        user.setTwoFactorEnabled(false);
        user.setTwoFactorSecret(null);
        user.updateTimestamp();
        User updatedUser = userRepository.save(user);

        log.info("2FA desabilitado com sucesso para usuário: {}", userId);
        return mapToResponseDTO(updatedUser);
    }

    @Override
    @Transactional
    public void setHideTwoFactorModal(String userId, boolean hide) {
        log.info("Definindo preferência de modal 2FA para usuário {}: {}", userId, hide);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado para definir preferência de modal: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        user.setHideTwoFactorModal(hide);
        user.updateTimestamp();
        userRepository.save(user);

        log.info("Preferência de modal 2FA atualizada para usuário: {}", userId);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponseDTO getUserById(String userId) {
        log.info("Buscando usuário por ID: {}", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Usuário não encontrado: {}", userId);
                    return new UserNotFoundException("Usuário não encontrado");
                });

        return mapToResponseDTO(user);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isEmailVerified(String email) {
        return userRepository.findByEmail(email)
                .map(User::isEmailVerified)
                .orElse(false);
    }

    @Override
    @Transactional
    public Optional<String> getOrCreateSignerVerificationToken(String email) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent() && existing.get().isEmailVerified()) {
            return Optional.empty();
        }

        User user = existing.orElseGet(() -> User.builder()
                .name(email)
                .email(email)
                .emailVerified(false)
                .twoFactorEnabled(false)
                .createdAt(LocalDateTime.now())
                .build());

        String token = user.getEmailVerificationToken();
        LocalDateTime expiry = user.getEmailVerificationExpiry();
        if (token == null || expiry == null || expiry.isBefore(LocalDateTime.now().plusMinutes(5))) {
            token = RandomStringUtils.randomAlphanumeric(64);
            user.setEmailVerificationToken(token);
            user.setEmailVerificationExpiry(LocalDateTime.now().plusHours(24));
        }
        user.updateTimestamp();
        userRepository.save(user);
        return Optional.of(token);
    }

    private User getValidUserForVerification(String token) {
        User user = userRepository.findByEmailVerificationToken(token)
                .orElseThrow(() -> {
                    log.warn("Token de verificação inválido");
                    return new InvalidTokenException("Token de verificação inválido ou expirado");
                });

        LocalDateTime expiry = user.getEmailVerificationExpiry();
        if (expiry == null || expiry.isBefore(LocalDateTime.now())) {
            log.warn("Token de verificação expirado para usuário: {}", user.getEmail());
            throw new InvalidTokenException("Token de verificação expirado. Solicite um novo email de verificação.");
        }

        return user;
    }

    /**
     * Maps a User entity to a UserResponseDTO.
     * Excludes sensitive information like password hash.
     * 
     * @param user the user entity
     * @return the user response DTO
     */
    @Override
    @Transactional
    public void requestPasswordReset(String email) {
        log.info("Solicitação de redefinição de senha para: {}", email);

        Optional<User> userOpt = userRepository.findByEmail(email);

        // Always same response regardless of whether user exists (prevents enumeration)
        if (userOpt.isEmpty() || !userOpt.get().isEmailVerified()) {
            log.info("Email não encontrado ou não verificado para reset: {}", email);
            return;
        }

        User user = userOpt.get();
        String resetToken = RandomStringUtils.randomAlphanumeric(64);
        user.setPasswordResetToken(resetToken);
        user.setPasswordResetExpiry(LocalDateTime.now().plusHours(1));
        user.updateTimestamp();
        userRepository.save(user);

        emailService.sendPasswordResetEmail(user.getEmail(), user.getName(), resetToken);
        log.info("Token de redefinição de senha gerado para: {}", email);
    }

    @Override
    @Transactional
    public void resetPassword(String token, String newPassword) {
        log.info("Redefinindo senha com token");

        User user = userRepository.findByPasswordResetToken(token)
                .orElseThrow(() -> new InvalidTokenException("Link de redefinição inválido ou expirado"));

        if (user.getPasswordResetExpiry() == null || user.getPasswordResetExpiry().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Link de redefinição expirado. Solicite um novo.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiry(null);
        user.updateTimestamp();
        userRepository.save(user);

        log.info("Senha redefinida com sucesso para: {}", user.getEmail());
    }

    private UserResponseDTO mapToResponseDTO(User user) {
        return UserResponseDTO.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .cpf(CpfValidator.mask(user.getCpf()))
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
