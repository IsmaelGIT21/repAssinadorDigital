package br.edu.utfpr.td.tsi.projeto_assinador.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filtroSeguranca(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requisicoes -> requisicoes .requestMatchers("/login", "/css/**", "/images/**", "/error").permitAll()
         .anyRequest().authenticated()).formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/sign-pdf").permitAll())
         .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll()).httpBasic(withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder codificadorSenha() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService usuarios(PasswordEncoder codificadorSenha,
            @Value("${app.usuario-teste.email:usuario@teste.com}") String email,
            @Value("${app.usuario-teste.senha:12345678}") String senha) {
        return new InMemoryUserDetailsManager(User.withUsername(email)
                .password(codificadorSenha.encode(senha))
                .roles("USER")
                .build());
    }
}
