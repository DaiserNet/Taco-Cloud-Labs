package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import javax.validation.Validator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;
import reactor.test.publisher.TestPublisher;
import tacos.TacoOrder;
import tacos.api.mapper.OrderMapper;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;

class OrderFromEmailControllerTest {

  private OrderRepository repo;
  private OrderMessagingService messaging;
  private EmailOrderService emailOrderService;
  private OrderApiController controller;

  @BeforeEach
  void setUp() {
    repo = mock(OrderRepository.class);
    messaging = mock(OrderMessagingService.class);
    emailOrderService = mock(EmailOrderService.class);
    controller = new OrderApiController(
        repo, messaging, emailOrderService, mock(Validator.class), new OrderMapper());
  }

  @Test
  void shouldSubscribeToColdConversionOnceAndPublishSavedOrderOnce() {
    TacoOrder converted = new TacoOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    PublisherProbe<TacoOrder> conversion = PublisherProbe.of(Mono.just(converted));
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(conversion.mono());
    when(repo.save(converted)).thenReturn(Mono.just(saved));

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder()))
        .assertNext(order -> assertEquals("ORDER-ID", order.getId()))
        .verifyComplete();

    assertEquals(1, conversion.subscribeCount());
    verify(repo, times(1)).save(converted);
    verify(messaging, times(1)).sendOrder(saved);
    InOrder interactions = inOrder(repo, messaging);
    interactions.verify(repo).save(converted);
    interactions.verify(messaging).sendOrder(saved);
  }

  @Test
  void shouldNotSaveOrPublishWhenConversionFails() {
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.error(new InvalidEmailOrderException("invalid email order")));

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder()))
        .expectError(InvalidEmailOrderException.class)
        .verify();

    verify(repo, never()).save(any(TacoOrder.class));
    verifyNoInteractions(messaging);
  }

  @Test
  void shouldNotPublishWhenSaveFails() {
    TacoOrder converted = new TacoOrder();
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted))
        .thenReturn(Mono.error(new IllegalStateException("save failed")));

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder()))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "save failed".equals(error.getMessage()))
        .verify();

    verifyNoInteractions(messaging);
  }

  @Test
  void shouldWaitForSaveBeforePublishing() {
    TacoOrder converted = new TacoOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    TestPublisher<TacoOrder> save = TestPublisher.createCold();
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted)).thenReturn(save.mono());

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder()))
        .expectSubscription()
        .then(() -> verifyNoInteractions(messaging))
        .expectNoEvent(Duration.ofMillis(20))
        .then(() -> save.emit(saved))
        .assertNext(order -> assertEquals("ORDER-ID", order.getId()))
        .verifyComplete();

    verify(messaging).sendOrder(saved);
  }

  @Test
  void shouldPropagatePublishFailureInsteadOfCompletingSuccessfully() {
    TacoOrder converted = new TacoOrder();
    TacoOrder saved = new TacoOrder();
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted)).thenReturn(Mono.just(saved));
    doThrow(new IllegalStateException("send failed"))
        .when(messaging).sendOrder(saved);

    StepVerifier.create(controller.postOrderFromEmail(new EmailOrder()))
        .expectErrorMatches(error -> error instanceof IllegalStateException
            && "send failed".equals(error.getMessage()))
        .verify();

    verify(repo).save(converted);
    verify(messaging).sendOrder(saved);
  }

  @Test
  void shouldReturnCreatedWithThePersistedOrder() throws Exception {
    TacoOrder converted = new TacoOrder();
    TacoOrder saved = new TacoOrder();
    saved.setId("ORDER-ID");
    when(emailOrderService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.just(converted));
    when(repo.save(converted)).thenReturn(Mono.just(saved));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    MvcResult result = mvc.perform(post("/api/orders/fromEmail")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"owner@example.test\",\"tacos\":["
                + "{\"name\":\"Valid taco\",\"ingredients\":[\"WRAP\"]}]}"))
        .andExpect(request().asyncStarted())
        .andReturn();

    mvc.perform(asyncDispatch(result))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("ORDER-ID"));
    verify(messaging).sendOrder(saved);
  }
}
