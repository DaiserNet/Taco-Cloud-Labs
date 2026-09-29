package tacos.web.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;
import tacos.api.dto.OrderHistoryPageResponse;
import tacos.api.dto.OrderResponse;
import tacos.api.dto.OrderSummaryResponse;
import tacos.api.mapper.OrderMapper;
import tacos.history.OrderHistoryService;

class OrderHistoryControllerTest {
  private final OrderHistoryService history = mock(OrderHistoryService.class);
  private final WebTestClient client = WebTestClient
      .bindToController(new OrderHistoryController(history)).build();

  @Test
  void shouldExposePrivatePageWithOnlySummaryFields() {
    OrderSummaryResponse summary = new OrderSummaryResponse("A", new Date(0),
        OrderStatus.PLACED, new BigDecimal("12.50"), "USD");
    when(history.myOrders(1, 2, null)).thenReturn(Mono.just(
        new OrderHistoryPageResponse(Arrays.asList(summary), 1, 2, 3, 2)));

    client.get().uri("/api/orders/me?page=1&size=2").exchange()
        .expectStatus().isOk().expectBody()
        .jsonPath("$.content[0].id").isEqualTo("A")
        .jsonPath("$.content[0].total").isEqualTo(12.50)
        .jsonPath("$.content[0].user").doesNotExist()
        .jsonPath("$.content[0].userId").doesNotExist()
        .jsonPath("$.content[0].paymentMethodId").doesNotExist()
        .jsonPath("$.content[0].paymentLast4").doesNotExist()
        .jsonPath("$.page").isEqualTo(1)
        .jsonPath("$.totalElements").isEqualTo(3);
  }

  @Test
  void shouldExposeSafeDetailWithoutUserOrPaymentToken() {
    TacoOrder order = new TacoOrder();
    order.setId("A");
    order.setUser(new User("alice", "secret-password", "Alice", "Street",
        "City", "ST", "12345", "5551234", "alice@example.com"));
    order.setPaymentMethodId("secret-token");
    order.setPaymentBrand("VISA");
    order.setPaymentLast4("1111");
    OrderResponse detail = new OrderMapper().toResponse(order);
    when(history.myOrder("A", null)).thenReturn(Mono.just(detail));

    client.get().uri("/api/orders/me/A").exchange()
        .expectStatus().isOk().expectBody()
        .jsonPath("$.id").isEqualTo("A")
        .jsonPath("$.paymentLast4").isEqualTo("1111")
        .jsonPath("$.user").doesNotExist()
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.paymentMethodId").doesNotExist()
        .jsonPath("$.paymentToken").doesNotExist();
  }

  @Test
  void shouldBindSeparateAdminFiltersAndRejectInvalidStatus() {
    when(history.adminOrders(0, 10, "ALICE", OrderStatus.PREPARING, null))
        .thenReturn(Mono.just(new OrderHistoryPageResponse(
            java.util.Collections.emptyList(), 0, 10, 0, 0)));

    client.get().uri("/api/admin/orders?userId=ALICE&status=PREPARING")
        .exchange().expectStatus().isOk().expectBody()
        .jsonPath("$.totalElements").isEqualTo(0);
    verify(history).adminOrders(0, 10, "ALICE", OrderStatus.PREPARING, null);
    client.get().uri("/api/admin/orders?status=UNKNOWN")
        .exchange().expectStatus().isBadRequest();
  }
}
