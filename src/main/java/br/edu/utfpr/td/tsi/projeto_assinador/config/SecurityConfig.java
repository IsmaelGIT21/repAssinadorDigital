package br.edu.utfpr.td.tsi.projeto_assinador.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
}
