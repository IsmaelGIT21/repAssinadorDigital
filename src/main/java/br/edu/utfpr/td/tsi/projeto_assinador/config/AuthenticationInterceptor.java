package br.edu.utfpr.td.tsi.projeto_assinador.config;

import java.util.List;
import java.util.Set;

import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AuthenticationInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "/login",
            "/register",
            "/verify-email",
            "/validate-2fa",
            "/validator",
            "/validate-document",
            "/forgot-password",
            "/reset-password",
            "/sign"
    );

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/css/",
            "/js/",
            "/images/",
            "/favicon"
    );

    private static final Set<String> NON_REDIRECT_PATHS = Set.of(
            "/error",
            "/favicon.ico"
    );

    @Override
    public boolean preHandle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull Object handler) throws Exception {

        String requestURI = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = requestURI.substring(contextPath.length());

        // Permite acesso aos endpoints públicos e recursos estáticos
        if (PUBLIC_ENDPOINTS.contains(path)
                || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith)
                || NON_REDIRECT_PATHS.contains(path)) {
            return true;
        }

        // Verifica se o usuário está autenticado
        HttpSession session = request.getSession(false);

        if (session != null && session.getAttribute("loggedUser") != null) {
            return true;
        }

        // Cria sessão para armazenar a URL original
        HttpSession newSession = request.getSession(true);

        if ("GET".equalsIgnoreCase(request.getMethod())) {
            // Armazena a URL SEM o context-path.
            // Exemplo: /documentos
            // e não: /assinador-utfpr-teste/documentos
            String redirectAfterLogin = path;

            String queryString = request.getQueryString();

            if (queryString != null && !queryString.isBlank()) {
                redirectAfterLogin += "?" + queryString;
            }

            newSession.setAttribute("redirectAfterLogin", redirectAfterLogin);
        }

        log.info("Usuário não autenticado tentando acessar: {}", requestURI);

        // O context-path é acrescentado somente no redirect.
        response.sendRedirect(contextPath + "/login");

        return false;
    }
}