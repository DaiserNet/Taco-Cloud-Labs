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
        .antMatchers("/api/admin/**").hasRole("ADMIN")
        .antMatchers(HttpMethod.GET, "/api/tacos/**", "/api/ingredients/**").permitAll()
        .antMatchers("/api/ingredients/**", "/api/tacos/**").hasRole("ADMIN")
        .antMatchers("/api/kitchen/**").hasRole("KITCHEN")
        .antMatchers("/api/payment-methods/**").hasRole("USER")
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
