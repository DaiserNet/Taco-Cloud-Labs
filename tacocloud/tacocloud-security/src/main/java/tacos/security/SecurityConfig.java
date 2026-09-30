package tacos.security;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation
             .authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web
             .builders.HttpSecurity;
import org.springframework.security.config.annotation.web
                        .configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web
                        .configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@SuppressWarnings("deprecation")
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {
  
  @Autowired
  private UserDetailsService userDetailsService;
  
  @Override
  protected void configure(HttpSecurity http) throws Exception {
    CookieCsrfTokenRepository csrfRepository =
        CookieCsrfTokenRepository.withHttpOnlyFalse();
    http
      .authorizeRequests()
        .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        .antMatchers("/", "/login", "/register", "/openapi.yaml",
            "/images/**", "/styles.css",
            "/favicon.ico").permitAll()
        .antMatchers("/ui", "/ui/**", "/assets/**",
            "/inline.bundle.js", "/polyfills.bundle.js",
            "/styles.bundle.js", "/vendor.bundle.js", "/main.bundle.js",
            "/Cloud_sm.*.png").permitAll()
        .antMatchers("/api/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.GET, "/api/tacos/**", "/api/ingredients/**",
            "/api/v1/tacos/**", "/api/v1/ingredients/**").permitAll()
        .antMatchers(HttpMethod.POST, "/api/tacos/validate", "/api/v1/tacos/validate")
            .hasAnyRole("USER", "ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/tacos/*/rating", "/api/v1/tacos/*/rating")
            .hasRole("USER")
        .antMatchers("/api/ingredients/**", "/api/tacos/**",
            "/api/v1/ingredients/**", "/api/v1/tacos/**").hasRole("ADMIN")
        .antMatchers("/api/kitchen/**", "/api/v1/kitchen/**").hasRole("KITCHEN")
        .antMatchers("/api/payment-methods/**", "/api/v1/payment-methods/**").hasRole("USER")
        .antMatchers(HttpMethod.GET, "/api/orders/me", "/api/orders/me/*",
            "/api/v1/orders/me", "/api/v1/orders/me/*")
            .hasRole("USER")
        .antMatchers(HttpMethod.GET, "/api/orders", "/api/orders/**",
            "/api/v1/orders", "/api/v1/orders/**")
            .denyAll()
        .antMatchers(HttpMethod.GET, "/api/users/me/favorites",
            "/api/v1/users/me/favorites").hasRole("USER")
        .antMatchers(HttpMethod.PUT, "/api/users/me/favorites/*",
            "/api/v1/users/me/favorites/*")
            .hasRole("USER")
        .antMatchers(HttpMethod.DELETE, "/api/users/me/favorites/*",
            "/api/v1/users/me/favorites/*")
            .hasRole("USER")
        .antMatchers(HttpMethod.PATCH, "/api/orders/*/status",
            "/api/v1/orders/*/status")
            .hasRole("ADMIN")
        .antMatchers(HttpMethod.POST, "/api/orders/*/cancel",
            "/api/v1/orders/*/cancel")
            .hasRole("USER")
        .antMatchers(HttpMethod.POST, "/api/orders/**", "/api/v1/orders/**")
            .hasRole("USER")
        .antMatchers("/api/orders/**", "/api/v1/orders/**")
            .hasAnyRole("USER", "ADMIN")
        .antMatchers("/api/favorites/**", "/api/ratings/**",
            "/api/v1/favorites/**", "/api/v1/ratings/**").hasRole("USER")
        .antMatchers("/data-api/**").hasRole("ADMIN")
        .antMatchers("/actuator/health", "/actuator/health/liveness",
            "/actuator/health/readiness").permitAll()
        .antMatchers("/actuator/**").hasRole("ADMIN")
        .anyRequest().denyAll()

      .and()
        .exceptionHandling()
          .defaultAuthenticationEntryPointFor(
              new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
              new OrRequestMatcher(
                  new AntPathRequestMatcher("/api/**"),
                  new AntPathRequestMatcher("/data-api/**"),
                  new AntPathRequestMatcher("/actuator/**")))
        
      .and()
        .formLogin()
          .loginPage("/login")
          
      .and()
        .httpBasic()
          .realmName("Taco Cloud")
          
      .and()
        .logout()
          .logoutSuccessUrl("/")
          
      .and()
        .cors()

      .and()
        .csrf()
          .csrfTokenRepository(csrfRepository)
          .ignoringAntMatchers("/h2-console/**")

      // Allow pages to be loaded in frames from the same origin; needed for H2-Console
      .and()  
        .headers()
          .frameOptions()
            .sameOrigin()
      ;
    http.addFilterAfter(new OncePerRequestFilter() {
      @Override
      protected void doFilterInternal(HttpServletRequest request,
          HttpServletResponse response, FilterChain chain)
          throws ServletException, IOException {
        if ("GET".equals(request.getMethod())
            && "/".equals(request.getRequestURI()
                .substring(request.getContextPath().length()))) {
          CsrfToken token = (CsrfToken) request.getAttribute(
              CsrfToken.class.getName());
          if (token != null) {
            token.getToken();
            csrfRepository.saveToken(token, request, response);
          }
        }
        chain.doFilter(request, response);
      }
    }, CsrfFilter.class);
  }

  @Bean
  public PasswordEncoder encoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }
  
  
  @Override
  protected void configure(AuthenticationManagerBuilder auth)
      throws Exception {

    auth
      .userDetailsService(userDetailsService)
      .passwordEncoder(encoder());
    
  }

}
