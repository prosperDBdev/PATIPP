package com.patipp.auth.internal;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Application security.
 *
 * <p>This lives in {@code auth} rather than {@code common} because it depends on the JWT
 * filter, and {@code common} must not depend on a feature module - an ArchUnit rule enforces
 * that. The direction matters: every module may use {@code common}, so if {@code common}
 * reached back into {@code auth} the dependency graph would have a cycle at its centre.
 *
 * <p>The API is stateless: identity arrives as a bearer token on every request. There is no
 * session and no cookie-borne ambient authority on an ordinary endpoint, so there is nothing
 * for CSRF to protect. The one cookie that exists - the refresh token - is
 * {@code SameSite=Strict} and scoped to {@code /api/v1/auth}, so a cross-site page cannot
 * cause the browser to send it at all.
 *
 * <p>Everything is denied unless listed. New endpoints are private by default, which is the
 * only default that fails safely when somebody forgets to think about it.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          CorsConfigurationSource corsConfigurationSource) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        // Preflight carries no credentials by design and must not 401.
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                writeProblem(response, HttpStatus.UNAUTHORIZED,
                                        "auth.required", "Authentication is required."))
                        .accessDeniedHandler((request, response, exception) ->
                                writeProblem(response, HttpStatus.FORBIDDEN,
                                        "auth.forbidden", "You do not have access to that resource.")))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Security-chain failures happen before any controller runs, so
     * {@code GlobalExceptionHandler} never sees them. Writing the same problem+json shape by
     * hand here means a 401 from the filter chain is indistinguishable, to a client, from a
     * 401 raised inside a service - so the frontend needs one error path, not two.
     */
    private void writeProblem(HttpServletResponse response, HttpStatus status,
                              String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"about:blank","title":"%s","status":%d,"detail":"%s","code":"%s"}"""
                .formatted(status.getReasonPhrase(), status.value(), detail, code));
    }

    /**
     * Strength 12 rather than the default 10. Each increment doubles the work: 12 keeps a
     * login around 200-300ms on ordinary hardware, imperceptible to a person, while making
     * an offline attack on a stolen hash dump four times more expensive.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
