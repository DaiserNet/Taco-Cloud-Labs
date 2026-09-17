package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Collections;
import javax.validation.Validator;

import org.springframework.http.MediaType;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;


import static org.springframework.test.web.servlet.request
    .MockMvcRequestBuilders.patch;

import static org.springframework.test.web.servlet.result
    .MockMvcResultMatchers.status;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@ContextConfiguration(classes = OrderApiController.class)
@WebFluxTest(controllers = OrderApiController.class)
public class OrderApiControllerTest {
    
    @Autowired 
    OrderApiController controller;

    @Autowired 
    WebTestClient testClient;

    @MockBean 
    OrderRepository repo;

    @MockBean
    private Validator validator;

    @MockBean
    private OrderMessagingService orderMessages;

    @MockBean
    private EmailOrderService emailOrderService;

    @Test 
    public void shouldPatchZipWithoutChangingState() {

        User owner = new User(
            "habuma", "password", "Craig Walls",
            "123 North Street", "Cross Roads", "TX", "76227",
            "123-123-1234", "craig@habuma.com"
        );

        TacoOrder order = new TacoOrder();
        order.setId("12345");
        order.setUser(owner);
        order.setDeliveryState("TX");
        order.setDeliveryZip("76227");
        
        OrderPatchRequest patch = new OrderPatchRequest();
        patch.setDeliveryZip("76228");

        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "habuma", "password", Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")));
        
        Mockito.when(repo.findById(order.getId())).thenReturn(Mono.just(order));
        
        Mockito.when(validator.validate(Mockito.any(TacoOrder.class))).thenReturn(Collections.emptySet());

        Mockito.when(repo.save(Mockito.any(TacoOrder.class))).thenAnswer(invocation -> {
            TacoOrder savedOrder = invocation.getArgument(0);
            return Mono.just(savedOrder);
        });

        StepVerifier.create(
            controller.patchOrder(order.getId(), patch, authentication)
        ).assertNext(reponse -> {
            assertEquals(HttpStatus.OK, reponse.getStatusCode());
            assertEquals("TX", reponse.getBody().getDeliveryState());
            assertEquals("76228", reponse.getBody().getDeliveryZip());
        }).verifyComplete();
        
        Mockito.verify(repo, Mockito.times(1)).save(Mockito.argThat(saved -> "76228".equals(saved.getDeliveryZip()) && 
            "TX".equals(saved.getDeliveryState())));
    }

    @Test 
    public void shouldRejectPatchFromDifferentUser() {
        User owner = new User(
            "habuma", "password", "Craig Walls",
            "123 North Street", "Cross Roads", "TX", "76227",
            "123-123-1234", "craig@habuma.com"
        );

        TacoOrder order = new TacoOrder();
        order.setId("12345");
        order.setUser(owner);
        order.setDeliveryState("TX");
        order.setDeliveryZip("76227");
        
        OrderPatchRequest patch = new OrderPatchRequest();
        patch.setDeliveryZip("76228");

        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "testuser", "password", Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")));

        Mockito.when(repo.findById("12345")).thenReturn(Mono.just(order));

        StepVerifier.create(controller.patchOrder("12345", patch, authentication))
            .assertNext(response -> {
                assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
            })
            .verifyComplete();
        Mockito.verify(repo, Mockito.never()).save(Mockito.any(TacoOrder.class));
    }

    @Test 
    public void shouldReturnNotFoundForNonexistentOrder() {
        OrderPatchRequest patch = new OrderPatchRequest();
        patch.setDeliveryZip("76228");

        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "habuma", "password", Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")));

        Mockito.when(repo.findById("nonexistent")).thenReturn(Mono.empty());

        StepVerifier.create(controller.patchOrder("nonexistent", patch, authentication))
            .assertNext(response -> {
                assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            })
            .verifyComplete();
        Mockito.verify(repo, Mockito.never()).save(Mockito.any(TacoOrder.class));
    }

    @Test 
    public void shouldRejectForbiddenPatchField() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(patch("/api/orders/ORDER1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"deliveryZip\":\"75001\","
                + "\"ccNumber\":\"41111111111111111\"}"))
            .andExpect(status().isBadRequest());
        Mockito.verifyNoInteractions(repo);
    }
}
