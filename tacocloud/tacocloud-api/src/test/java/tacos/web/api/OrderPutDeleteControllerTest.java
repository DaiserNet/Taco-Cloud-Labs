package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.test.publisher.TestPublisher;
import tacos.OrderStatus;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderResponse;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

@ExtendWith(MockitoExtension.class)
class OrderPutDeleteControllerTest {

  @Mock
  private OrderRepository repo;

  @Mock
  private OrderMessagingService orderMessages;

  @Mock
  private EmailOrderService emailOrderService;

  @Mock
  private Validator validator;

  private OrderApiController controller;

  @BeforeEach
  void setUp() {
    controller = new OrderApiController(
        repo, orderMessages, emailOrderService, validator, new OrderMapper());
  }

  @Test
  void shouldReplaceEditableOrderAndPreserveServerManagedFields() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    Date placedAt = new Date(123456789L);
    Taco taco = new Taco();
    taco.setId("TACO1");
    existing.setPlacedAt(placedAt);
    existing.setCcNumber("4111111111111111");
    existing.setCcCVV("123");
    existing.setCcExpiration("12/30");
    existing.setTacos(Collections.singletonList(taco));

    OrderReplaceRequest replacement = replacement();
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));
    when(repo.save(existing)).thenReturn(Mono.just(existing));

    StepVerifier.create(controller.putOrder("ORDER1", replacement, userAuthentication("habuma")))
        .assertNext(response -> {
          assertEquals(HttpStatus.OK, response.getStatusCode());
          OrderResponse saved = response.getBody();
          assertEquals("New name", saved.getDeliveryName());
          assertEquals("New street", saved.getDeliveryStreet());
          assertEquals("New city", saved.getDeliveryCity());
          assertEquals("CA", saved.getDeliveryState());
          assertEquals("90210", saved.getDeliveryZip());
          assertEquals("1111", saved.getPaymentLast4());
          assertSame(placedAt, existing.getPlacedAt());
          assertEquals("4111111111111111", existing.getCcNumber());
          assertEquals("123", existing.getCcCVV());
          assertEquals("12/30", existing.getCcExpiration());
          assertSame(taco, existing.getTacos().get(0));
        })
        .verifyComplete();

    verify(repo, times(1)).save(existing);
  }

  @Test
  void shouldRejectBodyIdBeforeRepositoryAccess() throws Exception {
    MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    mockMvc.perform(put("/api/orders/ROUTE-ID")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"id\":\"BODY-ID\",\"deliveryName\":\"New name\","
            + "\"deliveryStreet\":\"New street\",\"deliveryCity\":\"New city\","
            + "\"deliveryState\":\"CA\",\"deliveryZip\":\"90210\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(repo);
  }

  @Test
  void shouldReturnNotFoundWhenReplacingMissingOrder() {
    when(repo.findById("MISSING")).thenReturn(Mono.empty());

    StepVerifier.create(controller.putOrder("MISSING", replacement(), userAuthentication("habuma")))
        .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
        .verifyComplete();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  void shouldRejectReplacementFromDifferentUser() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));

    StepVerifier.create(controller.putOrder("ORDER1", replacement(), userAuthentication("other")))
        .assertNext(response -> assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode()))
        .verifyComplete();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  void shouldRejectReplacementWhenOrderIsNotEditable() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    existing.setStatus(OrderStatus.PREPARING);
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));

    StepVerifier.create(controller.putOrder("ORDER1", replacement(), userAuthentication("habuma")))
        .assertNext(response -> assertEquals(HttpStatus.CONFLICT, response.getStatusCode()))
        .verifyComplete();

    verify(repo, never()).save(any(TacoOrder.class));
  }

  @Test
  void shouldWaitForDeleteCompletionBeforeReturningNoContent() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    TestPublisher<Void> deletePublisher = TestPublisher.createCold();
    PublisherProbe<Void> deleteProbe = PublisherProbe.of(deletePublisher.mono());
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));
    when(repo.deleteById("ORDER1")).thenReturn(deleteProbe.mono());

    StepVerifier.create(controller.deleteOrder("ORDER1", userAuthentication("habuma")))
        .expectSubscription()
        .then(deleteProbe::assertWasSubscribed)
        .expectNoEvent(Duration.ofMillis(20))
        .then(deletePublisher::complete)
        .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
        .verifyComplete();

    verify(repo, times(1)).deleteById("ORDER1");
  }

  @Test
  void shouldReturnNotFoundWhenDeletingMissingOrder() {
    when(repo.findById("MISSING")).thenReturn(Mono.empty());

    StepVerifier.create(controller.deleteOrder("MISSING", userAuthentication("habuma")))
        .assertNext(response -> assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode()))
        .verifyComplete();

    verify(repo, never()).deleteById(any(String.class));
  }

  @Test
  void shouldRejectDeleteFromDifferentUser() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));

    StepVerifier.create(controller.deleteOrder("ORDER1", userAuthentication("other")))
        .assertNext(response -> assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode()))
        .verifyComplete();

    verify(repo, never()).deleteById(any(String.class));
  }

  @Test
  void shouldRejectDeleteWhenOrderIsNotEditable() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    existing.setStatus(OrderStatus.PREPARING);
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));

    StepVerifier.create(controller.deleteOrder("ORDER1", userAuthentication("habuma")))
        .assertNext(response -> assertEquals(HttpStatus.CONFLICT, response.getStatusCode()))
        .verifyComplete();

    verify(repo, never()).deleteById(any(String.class));
  }

  @Test
  void shouldAllowAdminToDeleteAnotherUsersOrder() {
    TacoOrder existing = editableOrder("ORDER1", owner());
    when(repo.findById("ORDER1")).thenReturn(Mono.just(existing));
    when(repo.deleteById("ORDER1")).thenReturn(Mono.empty());

    StepVerifier.create(controller.deleteOrder("ORDER1", adminAuthentication()))
        .assertNext(response -> assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode()))
        .verifyComplete();

    verify(repo, times(1)).deleteById("ORDER1");
  }

  private TacoOrder editableOrder(String id, User user) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setUser(user);
    order.setStatus(OrderStatus.PLACED);
    return order;
  }

  private OrderReplaceRequest replacement() {
    OrderReplaceRequest request = new OrderReplaceRequest();
    request.setDeliveryName("New name");
    request.setDeliveryStreet("New street");
    request.setDeliveryCity("New city");
    request.setDeliveryState("CA");
    request.setDeliveryZip("90210");
    return request;
  }

  private User owner() {
    return new User("habuma", "password", "Craig Walls", "123 North Street",
        "Cross Roads", "TX", "76227", "123-123-1234", "craig@habuma.com");
  }

  private Authentication userAuthentication(String username) {
    return new UsernamePasswordAuthenticationToken(username, "password",
        Arrays.asList(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private Authentication adminAuthentication() {
    return new UsernamePasswordAuthenticationToken("admin", "password",
        Arrays.asList(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }
}
