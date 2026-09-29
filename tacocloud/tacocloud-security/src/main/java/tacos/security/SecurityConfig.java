package tacos.security;

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
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@SuppressWarnings("deprecation")
@Configuration
@EnableWebSecurity
public class SecurityConfig extends WebSecurityConfigurerAdapter {
  
  @Autowired
  private UserDetailsService userDetailsService;
  
  @Override
  protected void configure(HttpSecurity http) throws Exception {
    http
      .authorizeRequests()
        .antMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        .antMatchers("/", "/login", "/register", "/images/**", "/styles.css",
            "/favicon.ico").permitAll()
        .antMatchers("/ui", "/ui/**", "/assets/**",
            "/inline.bundle.js", "/polyfills.bundle.js",
            "/styles.bundle.js", "/vendor.bundle.js", "/main.bundle.js",
            "/Cloud_sm.*.png").permitAll()
        .antMatchers("/api/admin/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.GET, "/api/tacos/**", "/api/ingredients/**").permitAll()
        .antMatchers(HttpMethod.POST, "/api/tacos/validate")
            .hasAnyRole("USER", "ADMIN")
        .antMatchers(HttpMethod.PUT, "/api/tacos/*/rating")
            .hasRole("USER")
        .antMatchers("/api/ingredients/**", "/api/tacos/**").hasRole("ADMIN")
        .antMatchers("/api/kitchen/**").hasRole("KITCHEN")
        .antMatchers("/api/payment-methods/**").hasRole("USER")
        .antMatchers(HttpMethod.GET, "/api/orders/me", "/api/orders/me/*")
            .hasRole("USER")
        .antMatchers(HttpMethod.GET, "/api/orders", "/api/orders/**")
            .denyAll()
        .antMatchers(HttpMethod.GET, "/api/users/me/favorites").hasRole("USER")
        .antMatchers(HttpMethod.PUT, "/api/users/me/favorites/*")
            .hasRole("USER")
        .antMatchers(HttpMethod.DELETE, "/api/users/me/favorites/*")
            .hasRole("USER")
        .antMatchers(HttpMethod.POST, "/api/orders/**").hasRole("USER")
        .antMatchers("/api/orders/**").hasAnyRole("USER", "ADMIN")
        .antMatchers("/api/favorites/**", "/api/ratings/**").hasRole("USER")
        .antMatchers("/data-api/**").hasRole("ADMIN")
        .antMatchers("/actuator/health").permitAll()
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
          .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
          .ignoringAntMatchers("/h2-console/**")

      // Allow pages to be loaded in frames from the same origin; needed for H2-Console
      .and()  
        .headers()
          .frameOptions()
            .sameOrigin()
      ;
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
