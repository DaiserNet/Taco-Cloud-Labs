package tacos.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tacos.OrderStatus;

class OrderWorkflowMatrixTest {
  @ParameterizedTest(name = "{0} -> {1} with {2}")
  @MethodSource("transitions")
  void shouldAllowOnlyTheDeclaredTransitionAndRole(
      OrderStatus from, OrderStatus to, String role) {
    assertEquals(expected(from, to, role),
        OrderWorkflowService.allows(from, to, role));
  }

  @ParameterizedTest
  @MethodSource("cancellationStates")
  void shouldCancelOnlyBeforePreparation(OrderStatus status,
      boolean expected) {
    assertEquals(expected, OrderWorkflowService.canCancel(status));
  }

  private static Stream<Arguments> transitions() {
    return Stream.of(OrderStatus.values())
        .flatMap(from -> Stream.of(OrderStatus.values())
            .flatMap(to -> Stream.of("ROLE_USER", "ROLE_KITCHEN", "ROLE_ADMIN")
                .map(role -> Arguments.of(from, to, role))));
  }

  private static Stream<Arguments> cancellationStates() {
    return Stream.of(OrderStatus.values()).map(status -> Arguments.of(status,
        status == OrderStatus.CREATED || status == OrderStatus.ACCEPTED));
  }

  private boolean expected(OrderStatus from, OrderStatus to, String role) {
    boolean preparation = "ROLE_KITCHEN".equals(role)
        || "ROLE_ADMIN".equals(role);
    boolean delivery = "ROLE_ADMIN".equals(role);
    switch (from) {
      case CREATED:
        return to == OrderStatus.ACCEPTED && "ROLE_KITCHEN".equals(role);
      case ACCEPTED:
        return to == OrderStatus.PREPARING && preparation;
      case PREPARING:
        return to == OrderStatus.READY && preparation;
      case READY:
        return to == OrderStatus.OUT_FOR_DELIVERY && delivery;
      case OUT_FOR_DELIVERY:
        return to == OrderStatus.DELIVERED && delivery;
      default:
        return false;
    }
  }
}
