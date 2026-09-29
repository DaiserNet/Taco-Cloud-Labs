package tacos.messaging;

public interface OrderMessagingService {
  void sendOrder(OrderEvent event);
}
